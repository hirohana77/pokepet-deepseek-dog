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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * DeepSeek 官方 API 异步交互客户端
 */
public class DeepSeekService {
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();
    private static final Random RANDOM = new Random();

    // 本地离线备选心声（保证断网或未配置有效Key时体验流畅）
    private static final String[] FALLBACK_THOUGHTS = {
            "主人在忙什么呢？汪呜~",
            "闻到了泥土和冒险的味道！汪！",
            "尾巴好痒，想转圈圈~",
            "今天天气真不错，适合出去刨骨头！",
            "肚子有点咕咕叫了，主人有肉肉吗？",
            "时刻警惕！任何怪物都别想伤害主人！",
            "主人的脚步好轻快呀，等等我！",
            "呼噜噜……好想找个暖和的地方打个滚~"
    };

    /**
     * 异步生成小狗对主人发言的回复
     *
     * @param playerMessage 主人发送的文本
     * @param contextInfo   游戏上下文状态（血量、环境等）
     * @return CompletableFuture 回复内容
     */
    public static CompletableFuture<String> askDeepSeek(String playerMessage, String contextInfo) {
        ModConfig config = ModConfig.get();
        if (!config.enableDeepSeek || config.apiKey == null || config.apiKey.trim().isEmpty() || config.apiKey.startsWith("YOUR_")) {
            return CompletableFuture.completedFuture(getFallbackReply(playerMessage));
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject requestBody = new JsonObject();
                requestBody.addProperty("model", config.model);
                requestBody.addProperty("temperature", config.temperature);
                requestBody.addProperty("max_tokens", 80);

                JsonArray messages = new JsonArray();

                // 系统设定
                JsonObject sysMsg = new JsonObject();
                sysMsg.addProperty("role", "system");
                String fullSystem = config.systemPrompt + "\n[当前游戏环境信息: " + contextInfo + "]";
                sysMsg.addProperty("content", fullSystem);
                messages.add(sysMsg);

                // 用户提问
                JsonObject userMsg = new JsonObject();
                userMsg.addProperty("role", "user");
                userMsg.addProperty("content", playerMessage);
                messages.add(userMsg);

                requestBody.add("messages", messages);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(config.apiUrl))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + config.apiKey)
                        .timeout(Duration.ofSeconds(15))
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
                } else {
                    System.err.println("[AICreater] DeepSeek API 响应非200: " + response.statusCode() + " " + response.body());
                }
            } catch (Exception e) {
                System.err.println("[AICreater] 请求 DeepSeek 失败: " + e.getMessage());
            }
            return getFallbackReply(playerMessage);
        });
    }

    /**
     * 异步生成环境自言自语（内心心声）
     */
    public static CompletableFuture<String> generateMindThought(String environmentDescription) {
        String prompt = "基于现在的环境，心里嘀咕一句心里话（简短可爱，不超过20个字）";
        return askDeepSeek(prompt, environmentDescription);
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
        return FALLBACK_THOUGHTS[RANDOM.nextInt(FALLBACK_THOUGHTS.length)];
    }
}
