package com.aicreater.client;

import com.aicreater.AICreaterMod;
import com.aicreater.client.renderer.CompanionDogRenderer;
import com.aicreater.client.screen.PetChatScreen;
import com.aicreater.client.screen.PetSkillScreen;
import com.aicreater.network.ModPackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import java.util.UUID;

/**
 * 模组客户端入口类
 */
public class AICreaterModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println("[AICreater] 正在初始化客户端渲染与交互界面 (支持宠物技能菜单与持久化对话)...");

        // 1. 注册伴侣小狗实体渲染器（附带头顶心声气泡、倍化体型与360°后空翻骨骼旋转动画）
        EntityRendererRegistry.register(AICreaterMod.COMPANION_DOG, CompanionDogRenderer::new);

        // 2. 注册服务端通知打开小狗专属聊天界面数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.S2C_OPEN_PET_SCREEN, (client, handler, buf, responseSender) -> {
            UUID dogUuid = buf.readUuid();
            int entityId = buf.readInt();
            String dogName = buf.readString();
            int affection = buf.readInt();

            client.execute(() -> {
                client.setScreen(new PetChatScreen(dogUuid, entityId, dogName, affection));
            });
        });

        // 3. 注册接收小狗 DeepSeek 回复数据包
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.S2C_PET_RESPONSE, (client, handler, buf, responseSender) -> {
            UUID dogUuid = buf.readUuid();
            int entityId = buf.readInt();
            String reply = buf.readString();

            client.execute(() -> {
                if (client.currentScreen instanceof PetChatScreen chatScreen) {
                    chatScreen.onReceiveReply(reply);
                }
            });
        });

        // 4. 注册服务端通知打开小狗专属技能指令菜单数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.S2C_OPEN_SKILL_MENU, (client, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            String dogName = buf.readString();
            boolean isFlying = buf.readBoolean();
            float scaleFactor = buf.readFloat();
            int affection = buf.readInt();

            client.execute(() -> {
                client.setScreen(new PetSkillScreen(entityId, dogName, isFlying, scaleFactor, affection));
            });
        });

        System.out.println("[AICreater] 客户端初始化完毕！三大技能轮盘已就绪。");
    }
}
