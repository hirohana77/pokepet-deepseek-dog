package com.aicreater.ai;

import com.aicreater.config.ModConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DeepSeek 官方 API 异步交互客户端
 * 采用【分轨式记忆物理隔离 + 令牌桶限频 + 微感知压缩】架构，极致兼顾体验与 Token 成本
 */
public class DeepSeekService {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    // 1. 仅限主宠深入对话的多轮历史记忆池（EntityID -> 对话记录列表，心声严禁写入此处）
    private static final Map<Integer, List<ChatMessage>> CONVERSATION_MEMORIES = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY_TURNS = 6; // 滑动窗口阈值：保留最近 6 轮问答

    // 2. 全局心声 API 令牌桶节流阀（防止多狗并发或高频心声导致 Token 爆炸或触发 429 限制）
    private static long lastMindApiCallTime = 0;
    private static final long MIN_MIND_API_INTERVAL_MS = 15000; // 整个世界内心声 API 请求最小强制间隔 15 秒

    public static class ChatMessage {
        public String role;
        public String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    // 精品拟真本地心声库（用于未联网、节流冷却中或混合生成的即时呈现）
    private static final String[] AMBIENT_THOUGHTS = {
            "闻到了泥土和冒险的味道！汪！",
            "尾巴好痒，想转圈圈~",
            "今天天气真不错，适合出去刨骨头！",
            "肚子有点咕咕叫了，主人有肉肉吗？",
            "主人的脚步好轻快呀，等等我！",
            "呼噜噜……好想找个暖和的地方打个滚~",
            "耳朵动了动，好像听到了小兔子的动静！",
            "主人的后背由我来守护！",
            "草地软乎乎的，踩着好舒服~",
            "汪呜~伸个大大的懒腰！"
    };

    /**
     * 【主道 1：专属对话框深度交互】
     * 仅在玩家主动打开 PetChatScreen 聊天框时调用。
     * 支持完整具身感知 + 多轮记忆滑动窗口压缩。
     */
    public static CompletableFuture<String> askDeepSeekChat(int entityId, String playerMessage, String fullRealTimeState) {
        ModConfig config = ModConfig.get();
        if (!config.enableDeepSeek || config.apiKey == null || config.apiKey.trim().isEmpty() || config.apiKey.startsWith("YOUR_")) {
            return CompletableFuture.completedFuture(getFallbackReply(playerMessage));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", config.model);
                requestBody.addProperty("temperature", config.temperature);
                requestBody.addProperty("max_tokens", 85); // 严格控制输出长度，防 token 膨胀

                JsonArray messages = new JsonArray();

                // 系统设定 + 实时具身感知全景快照
                JsonObject sysMsg = new JsonObject();
                sysMsg.addProperty("role", "system");
                String dynamicSystem = config.systemPrompt + "\n" +
                        "【当前世界与主人全景雷达】:\n" + fullRealTimeState + "\n" +
                        "(请结合主人目前的身体状态、手持物品、周围环境与威胁作出符合性格的反应，生动真实，语气可爱简短，不超过40字)";
                sysMsg.addProperty("content", dynamicSystem);
                messages.add(sysMsg);

                // 装载该小狗的历史对话记忆（滑动窗口）
                List<ChatMessage> history = CONVERSATION_MEMORIES.computeIfAbsent(entityId, k -> Collections.synchronizedList(new ArrayList<>()));
                synchronized (history) {
                    while (history.size() > MAX_HISTORY_TURNS * 2) {
                        history.remove(0);
                    }
                    for (ChatMessage msg : history) {
                        JsonObject hObj = new JsonObject();
                        hObj.addProperty("role", msg.role);
                        hObj.addProperty("content", msg.content);
                        messages.add(hObj);
                    }
                }

                // 压入当前玩家发言
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", playerMessage);
                messages.add(userMsg);

                requestBody.add("messages", messages);

                String reply = sendPostRequest(config, requestBody);
                if (reply != null && !reply.isEmpty()) {
                    // 仅将玩家的主动对话与小狗回答持久化到对话记忆池中
                    synchronized (history) {
                        history.add(new ChatMessage("user", playerMessage));
                        history.add(new ChatMessage("assistant", reply));
                    }
                    return reply;
                }
            } catch (Exception e) {
                System.err.println("[AICreater] 对话 API 请求失败: " + e.getMessage());
            }
            return getFallbackReply(playerMessage);
        });
    }

    /**
     * 【主道 2：头顶气泡心声轻量单向流（Stateless Mind Thought）】
     * 专为高频气泡场景设计：
     * 1. 物理隔离：绝不读写多轮历史对话，绝不污染主宠对话记忆；
     * 2. 微感知输入：只传简短微感知（约 30 字），砍掉 85% 以上 Input Token；
     * 3. 极速截断：Max Tokens 严格限制为 25；
     * 4. 令牌桶节流：全局 15 秒内最多请求 1 次 API，超频自动走精品本地心声库，0 额外开销！
     */
    public static CompletableFuture<String> generateMindThoughtStateless(String microContext) {
        ModConfig config = ModConfig.get();
        long now = System.currentTimeMillis();

        // 令牌桶频控保护：若距离上次心声请求不足 15 秒，直接走高质量本地心流，0 Token 开销！
        if (!config.enableDeepSeek || config.apiKey == null || config.apiKey.trim().isEmpty() || (now - lastMindApiCallTime < MIN_MIND_API_INTERVAL_MS)) {
            return CompletableFuture.completedFuture(AMBIENT_THOUGHTS[RANDOM.nextInt(AMBIENT_THOUGHTS.length)]);
        }

        // 50% 概率走本地应激，50% 走 AI 灵动，再次将 Token 开销腰斩 50%
        if (RANDOM.nextBoolean()) {
            return CompletableFuture.completedFuture(AMBIENT_THOUGHTS[RANDOM.nextInt(AMBIENT_THOUGHTS.length)]);
        }

        lastMindApiCallTime = now;

        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", config.model);
                requestBody.addProperty("temperature", 0.85);
                requestBody.addProperty("max_tokens", 25); // 单句心声严格控制在 15 个字内

                JsonArray messages = new JsonArray();

                // 极简微系统设定（仅 30 字符）
                JsonObject sysMsg = new JsonObject();
                sysMsg.addProperty("role", "system");
                sysMsg.addProperty("content", "你是忠诚可爱的小狗旺财。请根据当前简短环境在心底嘀咕一句话，语气呆萌，12字以内。");
                messages.add(sysMsg);

                // 微环境单轮输入（无任何多轮历史包裹）
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", "[微环境: " + microContext + "]");
                messages.add(userMsg);

                requestBody.add("messages", messages);

                String reply = sendPostRequest(config, requestBody);
                if (reply != null && !reply.isEmpty()) {
                    return reply;
                }
            } catch (Exception e) {
                System.err.println("[AICreater] 心声 API 节流回退: " + e.getMessage());
            }
            return AMBIENT_THOUGHTS[RANDOM.nextInt(AMBIENT_THOUGHTS.length)];
        });
    }

    private static String sendPostRequest(ModConfig config, JsonObject requestBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey)
                .timeout(Duration.ofSeconds(12))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(requestBody)))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            JsonObject json = GSON.fromJson(response.body(), JsonObject.class);
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                JsonObject choice = choices.get(0).getAsJsonObject();
                JsonObject msg = choice.getAsJsonObject("message");
                if (msg != null && msg.has("content")) {
                    return msg.get("content").getAsString().trim();
                }
            }
        }
        return null;
    }

    private static String getFallbackReply(String playerMessage) {
        if (playerMessage.contains("你好") || playerMessage.contains("嗨")) {
            return "汪汪！主人好呀，今天去哪里冒险？";
        }
        if (playerMessage.contains("吃") || playerMessage.contains("饿") || playerMessage.contains("骨头")) {
            return "吸溜……肉肉！骨头！旺财要吃！汪！";
        }
        if (playerMessage.contains("乖") || playerMessage.contains("摸")) {
            return "呼噜噜~最喜欢主人的抚摸啦！(摇尾巴)";
        }
        return AMBIENT_THOUGHTS[RANDOM.nextInt(AMBIENT_THOUGHTS.length)];
    }
}
