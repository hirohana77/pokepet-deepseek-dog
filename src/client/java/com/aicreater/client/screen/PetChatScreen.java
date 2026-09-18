package com.aicreater.client.screen;

import com.aicreater.client.chat.PetChatHistoryManager;
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
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 伴侣小狗专属拟人化聊天对话界面
 * 1. 支持【小狗UUID + 玩家UUID】双重复合绑定与持久化保存
 * 2. 优先加载最新 10 条对话，避免一次性加载过长内容导致卡顿
 * 3. 支持鼠标滚轮渐进式（流式翻页）向上查看更早的历史记录
 */
public class PetChatScreen extends Screen {
    private final UUID dogUuid;
    private final int dogEntityId;
    private final String dogName;
    private final int affection;
    private final UUID playerUuid;

    private TextFieldWidget inputField;
    private ButtonWidget sendButton;

    // 当前加载的历史记录数量（默认优先加载最新 10 条，支持向上滚轮渐进式增加）
    private int currentLoadLimit = 10;
    private int scrollOffset = 0; // 滚动行数偏移

    private final List<String> rawHistoryLines = new ArrayList<>();

    public PetChatScreen(UUID dogUuid, int dogEntityId, String dogName, int affection) {
        super(Text.literal("与 " + dogName + " 对话"));
        this.dogUuid = dogUuid;
        this.dogEntityId = dogEntityId;
        this.dogName = dogName;
        this.affection = affection;
        this.playerUuid = MinecraftClient.getInstance().player != null ? MinecraftClient.getInstance().player.getUuid() : UUID.randomUUID();

        // 首次打开优先加载最新 10 条持久化记录
        refreshHistoryFromStorage();
    }

    private void refreshHistoryFromStorage() {
        rawHistoryLines.clear();
        List<PetChatHistoryManager.ChatRecord> records = PetChatHistoryManager.getRecentRecords(this.dogUuid, this.playerUuid, this.currentLoadLimit);

        if (records.isEmpty()) {
            rawHistoryLines.add("§6[" + dogName + "]§r 汪汪！主人找我有什么事吗？");
        } else {
            for (PetChatHistoryManager.ChatRecord rec : records) {
                if ("player".equals(rec.sender)) {
                    rawHistoryLines.add("§a[主人]§r " + rec.text);
                } else {
                    rawHistoryLines.add("§6[" + dogName + "]§r " + rec.text);
                }
            }
        }
    }

    @Override
    protected void init() {
        super.init();

        int cardWidth = 360;
        int cardHeight = 220;
        int cardX = (this.width - cardWidth) / 2;
        int cardY = (this.height - cardHeight) / 2;

        this.inputField = new TextFieldWidget(this.textRenderer, cardX + 16, cardY + cardHeight - 34, cardWidth - 86, 20, Text.literal("输入框"));
        this.inputField.setMaxLength(120);
        this.addSelectableChild(this.inputField);
        this.setInitialFocus(this.inputField);

        this.sendButton = ButtonWidget.builder(Text.literal("发送"), button -> sendCurrentMessage())
                .dimensions(cardX + cardWidth - 66, cardY + cardHeight - 34, 50, 20)
                .build();
        this.addDrawableChild(this.sendButton);

        // 标题栏右侧 API 配置按钮 (⚙ 配置)
        ButtonWidget configButton = ButtonWidget.builder(Text.literal("⚙ API 配置"), button -> {
            if (this.client != null) {
                this.client.setScreen(new PetConfigScreen(this));
            }
        }).dimensions(cardX + cardWidth - 78, cardY + 6, 68, 18).build();
        this.addDrawableChild(configButton);
    }

    private void sendCurrentMessage() {
        String msg = this.inputField.getText().trim();
        if (msg.isEmpty()) return;

        // 1. 追加并持久化保存玩家消息
        PetChatHistoryManager.appendRecord(this.dogUuid, this.playerUuid, "player", msg);
        rawHistoryLines.add("§a[主人]§r " + msg);
        rawHistoryLines.add("§7[" + dogName + " 思考中...]§r");
        this.inputField.setText("");
        this.scrollOffset = 0; // 发送后自动滚动到最新

        // 2. 发送网络包
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeUuid(this.dogUuid);
        buf.writeInt(this.dogEntityId);
        buf.writeString(msg);
        ClientPlayNetworking.send(ModPackets.C2S_PET_CHAT, buf);

        if (this.client != null && this.client.player != null) {
            this.client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.8F, 1.0F);
        }
    }

    /**
     * 接收并持久化保存小狗回复
     */
    public void onReceiveReply(String reply) {
        if (!rawHistoryLines.isEmpty() && rawHistoryLines.get(rawHistoryLines.size() - 1).contains("思考中")) {
            rawHistoryLines.remove(rawHistoryLines.size() - 1);
        }
        rawHistoryLines.add("§6[" + dogName + "]§r " + reply);
        this.scrollOffset = 0;

        // 持久化写入磁盘
        PetChatHistoryManager.appendRecord(this.dogUuid, this.playerUuid, "dog", reply);
    }

    /**
     * 鼠标滚轮渐进式向上加载更远历史记录
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (amount > 0) {
            // 向上滚动：查看更远历史，若接近顶部则渐进式扩大加载窗口（+5条）
            scrollOffset++;
            int total = PetChatHistoryManager.getTotalRecordCount(this.dogUuid, this.playerUuid);
            if (this.currentLoadLimit < total) {
                this.currentLoadLimit = Math.min(total, this.currentLoadLimit + 5);
                refreshHistoryFromStorage();
            }
            return true;
        } else if (amount < 0) {
            // 向下滚动：查看最新
            if (scrollOffset > 0) {
                scrollOffset--;
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            sendCurrentMessage();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        int cardWidth = 360;
        int cardHeight = 220;
        int cardX = (this.width - cardWidth) / 2;
        int cardY = (this.height - cardHeight) / 2;

        context.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xEE1E1E2E);
        context.fill(cardX + 2, cardY + 2, cardX + cardWidth - 2, cardY + 28, 0xFF2A2A3E);

        // 标题栏：显示小狗名字与好感度
        context.drawText(this.textRenderer, "🐾 " + this.dogName + " 的心灵小窝", cardX + 12, cardY + 10, 0xFFFFFFFF, false);
        String affectionText = "❤ 好感度: " + this.affection;
        context.drawText(this.textRenderer, affectionText, cardX + cardWidth - this.textRenderer.getWidth(affectionText) - 14, cardY + 10, 0xFFFF69B4, false);

        // 滚动提示角标（若有更早历史）
        int totalStored = PetChatHistoryManager.getTotalRecordCount(this.dogUuid, this.playerUuid);
        if (totalStored > this.currentLoadLimit) {
            context.drawText(this.textRenderer, "↑ 向上滚轮加载更多历史", cardX + 16, cardY + 31, 0xFF8888AA, false);
        }

        // 计算自动换行文本
        int maxWrapWidth = cardWidth - 36;
        List<OrderedText> allWrappedLines = new ArrayList<>();
        for (String rawMsg : rawHistoryLines) {
            List<OrderedText> wrapped = this.textRenderer.wrapLines(Text.literal(rawMsg), maxWrapWidth);
            allWrappedLines.addAll(wrapped);
        }

        // 启用视口物理裁剪矩形
        context.enableScissor(cardX + 10, cardY + 32, cardX + cardWidth - 10, cardY + cardHeight - 38);

        int maxVisibleLines = 9;
        int totalLines = allWrappedLines.size();

        // 结合 scrollOffset 计算起始行（默认贴底显示最新）
        int bottomIdx = totalLines - 1 - scrollOffset;
        int startIdx = Math.max(0, bottomIdx - maxVisibleLines + 1);

        int historyStartY = cardY + 36;
        int renderLineCount = 0;
        for (int i = startIdx; i <= Math.min(bottomIdx, totalLines - 1); i++) {
            if (i >= 0 && i < totalLines) {
                OrderedText line = allWrappedLines.get(i);
                context.drawText(this.textRenderer, line, cardX + 16, historyStartY + renderLineCount * 14, 0xFFDDDDDD, false);
                renderLineCount++;
            }
        }

        context.disableScissor();

        this.inputField.render(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
