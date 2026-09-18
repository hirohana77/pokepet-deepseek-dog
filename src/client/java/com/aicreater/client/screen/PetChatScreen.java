package com.aicreater.client.screen;

import com.aicreater.network.ModPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 伴侣小狗专属拟人化聊天对话界面
 */
public class PetChatScreen extends Screen {
    private final int dogEntityId;
    private final String dogName;
    private final int affection;

    private TextFieldWidget inputField;
    private ButtonWidget sendButton;
    private final List<String> chatHistory = new ArrayList<>();

    public PetChatScreen(int dogEntityId, String dogName, int affection) {
        super(Text.literal("与 " + dogName + " 对话"));
        this.dogEntityId = dogEntityId;
        this.dogName = dogName;
        this.affection = affection;

        // 默认欢迎语
        chatHistory.add("§6[" + dogName + "]§r 汪汪！主人找我有什么事吗？");
    }

    @Override
    protected void init() {
        super.init();

        int cardWidth = 320;
        int cardHeight = 210;
        int cardX = (this.width - cardWidth) / 2;
        int cardY = (this.height - cardHeight) / 2;

        // 对话输入框
        this.inputField = new TextFieldWidget(this.textRenderer, cardX + 16, cardY + cardHeight - 34, cardWidth - 90, 20, Text.literal("输入框"));
        this.inputField.setMaxLength(120);
        this.addSelectableChild(this.inputField);
        this.setInitialFocus(this.inputField);

        // 发送按钮
        this.sendButton = ButtonWidget.builder(Text.literal("发送"), button -> sendCurrentMessage())
                .dimensions(cardX + cardWidth - 68, cardY + cardHeight - 34, 52, 20)
                .build();
        this.addDrawableChild(this.sendButton);
    }

    private void sendCurrentMessage() {
        String msg = this.inputField.getText().trim();
        if (msg.isEmpty()) return;

        // 追加玩家发言记录
        chatHistory.add("§a[主人]§r " + msg);
        chatHistory.add("§7[" + dogName + " 思考中...]§r");
        this.inputField.setText("");

        // 发送 C2S 数据包给服务端
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(this.dogEntityId);
        buf.writeString(msg);
        ClientPlayNetworking.send(ModPackets.C2S_PET_CHAT, buf);

        if (this.client != null && this.client.player != null) {
            this.client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.8F, 1.0F);
        }
    }

    /**
     * 接收服务端传回的小狗回复
     */
    public void onReceiveReply(String reply) {
        // 替换最后一条“思考中”
        if (!chatHistory.isEmpty() && chatHistory.get(chatHistory.size() - 1).contains("思考中")) {
            chatHistory.remove(chatHistory.size() - 1);
        }
        chatHistory.add("§6[" + dogName + "]§r " + reply);
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
        // 渲染半透明背景
        this.renderBackground(context);

        int cardWidth = 320;
        int cardHeight = 210;
        int cardX = (this.width - cardWidth) / 2;
        int cardY = (this.height - cardHeight) / 2;

        // 绘制主卡片对话框底板（深蓝暗灰渐变质感）
        context.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xEE1E1E2E);
        context.fill(cardX + 2, cardY + 2, cardX + cardWidth - 2, cardY + 28, 0xFF2A2A3E);

        // 标题栏：小狗名字与亲密度状态
        context.drawText(this.textRenderer, "🐾 " + this.dogName + " 的心灵小窝", cardX + 12, cardY + 10, 0xFFFFFFFF, false);
        String affectionText = "❤ 好感度: " + this.affection;
        context.drawText(this.textRenderer, affectionText, cardX + cardWidth - this.textRenderer.getWidth(affectionText) - 14, cardY + 10, 0xFFFF69B4, false);

        // 渲染对话历史列表（自适应末尾多行）
        int historyStartY = cardY + 36;
        int maxLines = 8;
        int startIdx = Math.max(0, chatHistory.size() - maxLines);
        for (int i = startIdx; i < chatHistory.size(); i++) {
            context.drawText(this.textRenderer, chatHistory.get(i), cardX + 16, historyStartY + (i - startIdx) * 16, 0xFFDDDDDD, false);
        }

        // 渲染输入框与按钮控件
        this.inputField.render(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false; // 联机友好，打开对话框时不强制暂停游戏
    }
}
