package com.aicreater.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

/**
 * 模组全局配置文件管理器
 */
public class ModConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "aicreater.json";
    private static ModConfig INSTANCE;

    // 开源安全：默认代码库绝不硬编码任何私密 Key，由本地 config/aicreater.json 或游戏内 UI 配置保存
    public String apiKey = "";
    public String apiUrl = "https://api.deepseek.com/chat/completions";
    public String model = "deepseek-chat";
    public String systemPrompt = "你是一只生活在《Minecraft》世界里、忠诚且通人性的智能小狗，名字叫旺财。你能感知主人的生命值、周围的怪物、天气与地形环境。你的回答风格活泼可爱、会撒娇、会吐槽，偶尔会带上“汪呜~”、“汪汪！”等小狗语气。回答必须简短精炼（1至2句话以内，不超过40字），生动拟人化。";
    public int mindIntervalSeconds = 40;
    public boolean enableDeepSeek = true;
    public double temperature = 0.8;

    public static ModConfig get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    public static void load() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        File configFile = configDir.resolve(FILE_NAME).toFile();

        if (configFile.exists()) {
            try (FileReader reader = new FileReader(configFile)) {
                INSTANCE = GSON.fromJson(reader, ModConfig.class);
            } catch (Exception e) {
                System.err.println("[AICreater] 读取配置文件失败，使用默认配置: " + e.getMessage());
                INSTANCE = new ModConfig();
            }
        } else {
            INSTANCE = new ModConfig();
            save();
        }
    }

    public static void save() {
        if (INSTANCE == null) return;
        Path configDir = FabricLoader.getInstance().getConfigDir();
        File configFile = configDir.resolve(FILE_NAME).toFile();

        try {
            if (!configFile.getParentFile().exists()) {
                configFile.getParentFile().mkdirs();
            }
            try (FileWriter writer = new FileWriter(configFile)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (IOException e) {
            System.err.println("[AICreater] 保存配置文件失败: " + e.getMessage());
        }
    }
}
