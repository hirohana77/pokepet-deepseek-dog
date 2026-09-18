package com.aicreater.client.screen;

import com.aicreater.network.ModPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

/**
 * 宠物专属技能指令控制面板（UI）
 * 包含三大酷炫指令：直升机螺旋尾巴起飞、巨犬倍化变大、杂技 360° 后空翻
 */
public class PetSkillScreen extends Screen {
    private final int dogEntityId;
    private final String dogName;
    private boolean isFlying;
    private float scaleFactor;
    private final int affection;

    public PetSkillScreen(int dogEntityId, String dogName, boolean isFlying, float scaleFactor, int affection) {
        super(Text.literal(dogName + " 的技能指令中心"));
        this.dogEntityId = dogEntityId;
        this.dogName = dogName;
        this.isFlying = isFlying;
        this.scaleFactor = scaleFactor;
        this.affection = affection;
    }

    @Override
    protected void init() {
        super.init();

        int cardWidth = 280;
        int cardHeight = 190;
        int cardX = (this.width - cardWidth) / 2;
        int cardY = (this.height - cardHeight) / 2;

        int btnWidth = cardWidth - 32;
        int btnHeight = 24;
        int startY = cardY + 40;

        // 1. 螺旋起飞按钮
        String flyText = this.isFlying ? "🚁 【降落回到地面】" : "🚁 【螺旋尾巴起飞（空中巡航）】";
        ButtonWidget flyButton = ButtonWidget.builder(Text.literal(flyText), button -> {
            sendSkillCommand(1);
            this.isFlying = !this.isFlying;
            this.close();
        }).dimensions(cardX + 16, startY, btnWidth, btnHeight).build();
        this.addDrawableChild(flyButton);

        // 2. 巨犬倍化术按钮
        boolean isGiant = this.scaleFactor > 1.5F;
        String giantText = isGiant ? "🐕 【恢复小巧常态】" : "🦖 【巨犬倍化术（2.6倍体型，防窒息）】";
        ButtonWidget giantButton = ButtonWidget.builder(Text.literal(giantText), button -> {
            sendSkillCommand(2);
            this.close();
        }).dimensions(cardX + 16, startY + 34, btnWidth, btnHeight).build();
        this.addDrawableChild(giantButton);

        // 3. 杂技 360° 后空翻按钮
        ButtonWidget flipButton = ButtonWidget.builder(Text.literal("🤸 【表演杂技 360° 后空翻】"), button -> {
            sendSkillCommand(3);
            this.close();
        }).dimensions(cardX + 16, startY + 68, btnWidth, btnHeight).build();
        this.addDrawableChild(flipButton);

        // 4. 关闭菜单按钮
        ButtonWidget closeButton = ButtonWidget.builder(Text.literal("关闭指令面板"), button -> this.close())
                .dimensions(cardX + 16, startY + 106, btnWidth, 20)
                .build();
        this.addDrawableChild(closeButton);
    }

    private void sendSkillCommand(int skillId) {
        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(this.dogEntityId);
        buf.writeInt(skillId);
        ClientPlayNetworking.send(ModPackets.C2S_TRIGGER_PET_SKILL, buf);

        if (this.client != null && this.client.player != null) {
            this.client.player.playSound(SoundEvents.UI_BUTTON_CLICK.value(), 0.9F, 1.0F);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        int cardWidth = 280;
        int cardHeight = 190;
        int cardX = (this.width - cardWidth) / 2;
        int cardY = (this.height - cardHeight) / 2;

        // 绘制质感深色底板
        context.fill(cardX, cardY, cardX + cardWidth, cardY + cardHeight, 0xEE1E1E2E);
        context.fill(cardX + 2, cardY + 2, cardX + cardWidth - 2, cardY + 28, 0xFF2A2A3E);

        // 标题栏
        context.drawText(this.textRenderer, "⚡ " + this.dogName + " 的技能轮盘", cardX + 14, cardY + 10, 0xFFFFCC00, false);
        String affectionText = "❤ " + this.affection;
        context.drawText(this.textRenderer, affectionText, cardX + cardWidth - this.textRenderer.getWidth(affectionText) - 14, cardY + 10, 0xFFFF69B4, false);

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
