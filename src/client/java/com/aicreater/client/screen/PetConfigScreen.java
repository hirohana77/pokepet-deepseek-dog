package com.aicreater.client.screen;

import com.aicreater.config.ModConfig;
import com.aicreater.network.ModPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * 游戏内 DeepSeek API 本地配置管理界面
 * 允许玩家在游戏内直接配置 Key、模型与 URL，并安全持久化至本地磁盘 config/aicreater.json，代码仓库零泄漏
 */
public class PetConfigScreen extends Screen {
    private final Screen parentScreen;

    private TextFieldWidget apiKeyField;
    private TextFieldWidget modelField;
    private TextFieldWidget apiUrlField;

    public PetConfigScreen(Screen parentScreen) {
        super(Text.literal("DeepSeek API 配置"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        super.init();

        int cardWidth = 320;
        int cardHeight = 220;
        int cardX = (this.width - cardWidth) / 2;
        int cardY = (this.height - cardHeight) / 2;

        ModConfig config = ModConfig.get();

        // 1. API Key 输入框
        this.apiKeyField = new TextFieldWidget(this.textRenderer, cardX + 16, cardY + 46, cardWidth - 32, 20, Text.literal("API Key"));
        this.apiKeyField.setMaxLength(256);
        this.apiKeyField.setText(config.apiKey != null ? config.apiKey : "");
        this.addSelectableChild(this.apiKeyField);

        // 2. 模型输入框
        this.modelField = new TextFieldWidget(this.textRenderer, cardX + 16, cardY + 92, cardWidth - 32, 20, Text.literal("模型名称"));
        this.modelField.setMaxLength(64);
        this.modelField.setText(config.model != null ? config.model : "deepseek-chat");
        this.addSelectableChild(this.modelField);

        // 3. API URL 输入框
        this.apiUrlField = new TextFieldWidget(this.textRenderer, cardX + 16, cardY + 138, cardWidth - 32, 20, Text.literal("API 端点"));
        this.apiUrlField.setMaxLength(256);
        this.apiUrlField.setText(config.apiUrl != null ? config.apiUrl : "https://api.deepseek.com/chat/completions");
        this.addSelectableChild(this.apiUrlField);

        // 保存并应用按钮
        ButtonWidget saveButton = ButtonWidget.builder(Text.literal("保存并应用"), button -> saveAndApply())
                .dimensions(cardX + 16, cardY + 176, 136, 22)
                .build();
        this.addDrawableChild(saveButton);

        // 返回按钮
        ButtonWidget backButton = ButtonWidget.builder(Text.literal("返回"), button -> {
            if (this.client != null) {
                this.client.setScreen(this.parentScreen);
            }
        }).dimensions(cardX + cardWidth - 152, cardY + 176, 136, 22).build();
        this.addDrawableChild(backButton);
    }

    private void saveAndApply() {
        String key = this.apiKeyField.getText().trim();
        String model = this.modelField.getText().trim();
        String url = this.apiUrlField.getText().trim();

        // 更新本地配置
        ModConfig config = ModConfig.get();
        config.apiKey = key;
        if (!model.isEmpty()) config.model = model;
        if (!url.isEmpty()) config.apiUrl = url;

        // 本地安全持久化保存到 .minecraft/config/aicreater.json
        ModConfig.save();

        // 发送 C2S 数据包同步给服务端当前运行实例
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeString(key);
        buf.writeString(model);
        buf.writeString(url);
        ClientPlayNetworking.send(ModPackets.C2S_UPDATE_CONFIG, buf);

        if (this.client != null) {
            if (this.client.player != null) {
                this.client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F, 1.0F);
                this.client.player.sendMessage(Text.literal("§a✨ DeepSeek 配置已保存并持久化至本地 config 目录！"), true);
            }
            this.client.setScreen(this.parentScreen);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        int cardWidth = 320;
        int cardHeight = 220;
        int cardX = (this.width - cardWidth) / 2;
        int cardY = (this.height - cardHeight) / 2;

        context.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xEE1E1E2E);
        context.fill(cardX + 2, cardY + 2, cardX + cardWidth - 2, cardY + 26, 0xFF2A2A3E);

        // 标题
        context.drawText(this.textRenderer, "⚙ DeepSeek 官方 API 配置", cardX + 12, cardY + 9, 0xFFFFCC00, false);

        // 标签提示
        context.drawText(this.textRenderer, "API Key (sk-...):", cardX + 16, cardY + 34, 0xFFAAAAAA, false);
        context.drawText(this.textRenderer, "模型名称 (默认 deepseek-chat):", cardX + 16, cardY + 80, 0xFFAAAAAA, false);
        context.drawText(this.textRenderer, "API 端点 (Base URL):", cardX + 16, cardY + 126, 0xFFAAAAAA, false);

        this.apiKeyField.render(context, mouseX, mouseY, delta);
        this.modelField.render(context, mouseX, mouseY, delta);
        this.apiUrlField.render(context, mouseX, mouseY, delta);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
