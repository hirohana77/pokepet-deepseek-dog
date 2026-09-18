package com.aicreater.client.chat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 宠物聊天记录持久化管理器
 * 采用【小狗 UUID + 角色 UUID】复合双重绑定，完美支持多狗对多主人的复杂场景
 */
public class PetChatHistoryManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "aicreater_chat_history.json";

    // Key 格式: "<DogUUID>_<PlayerUUID>" -> 完整的问答记录列表
    private static final Map<String, List<ChatRecord>> HISTORY_CACHE = new ConcurrentHashMap<>();
    private static boolean loaded = false;

    public static class ChatRecord {
        public String sender; // "player" 或 "dog"
        public String text;
        public long timestamp;

        public ChatRecord(String sender, String text, long timestamp) {
            this.sender = sender;
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    private static String buildKey(UUID dogUuid, UUID playerUuid) {
        return (dogUuid != null ? dogUuid.toString() : "global") + "_" + (playerUuid != null ? playerUuid.toString() : "global");
    }

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        Path configDir = FabricLoader.getInstance().getConfigDir();
        File file = configDir.resolve(FILE_NAME).toFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                Type type = new TypeToken<Map<String, List<ChatRecord>>>() {}.getType();
                Map<String, List<ChatRecord>> data = GSON.fromJson(reader, type);
                if (data != null) {
                    HISTORY_CACHE.putAll(data);
                }
            } catch (Exception e) {
                System.err.println("[AICreater] 读取宠物对话历史失败: " + e.getMessage());
            }
        }
    }

    public static synchronized void save() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        File file = configDir.resolve(FILE_NAME).toFile();
        try {
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(HISTORY_CACHE, writer);
            }
        } catch (Exception e) {
            System.err.println("[AICreater] 保存宠物对话历史失败: " + e.getMessage());
        }
    }

    /**
     * 追加单条对话记录并持久化
     */
    public static void appendRecord(UUID dogUuid, UUID playerUuid, String sender, String text) {
        load();
        String key = buildKey(dogUuid, playerUuid);
        List<ChatRecord> list = HISTORY_CACHE.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()));
        synchronized (list) {
            list.add(new ChatRecord(sender, text, System.currentTimeMillis()));
            // 单一关系最多保留最近 200 条完整历史
            while (list.size() > 200) {
                list.remove(0);
            }
        }
        // 异步写回磁盘，不卡顿渲染主线程
        new Thread(PetChatHistoryManager::save, "AICreater-History-Saver").start();
    }

    /**
     * 获取指定条数的最新记录（默认优先取最新 10 条，避免一次性加载过多导致卡顿）
     */
    public static List<ChatRecord> getRecentRecords(UUID dogUuid, UUID playerUuid, int limit) {
        load();
        String key = buildKey(dogUuid, playerUuid);
        List<ChatRecord> fullList = HISTORY_CACHE.get(key);
        if (fullList == null || fullList.isEmpty()) {
            return new ArrayList<>();
        }

        synchronized (fullList) {
            int total = fullList.size();
            int start = Math.max(0, total - limit);
            return new ArrayList<>(fullList.subList(start, total));
        }
    }

    /**
     * 获取指定范围的渐进式历史记录（用于滚轮往上翻阅）
     */
    public static List<ChatRecord> getPagedRecords(UUID dogUuid, UUID playerUuid, int count) {
        return getRecentRecords(dogUuid, playerUuid, count);
    }

    /**
     * 获取该小狗与玩家的历史总条数
     */
    public static int getTotalRecordCount(UUID dogUuid, UUID playerUuid) {
        load();
        String key = buildKey(dogUuid, playerUuid);
        List<ChatRecord> list = HISTORY_CACHE.get(key);
        return list != null ? list.size() : 0;
    }
}
