package com.aicreater.client;

import com.aicreater.AICreaterMod;
import com.aicreater.client.renderer.CompanionDogRenderer;
import com.aicreater.client.screen.PetChatScreen;
import com.aicreater.network.ModPackets;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.MinecraftClient;

/**
 * 模组客户端入口类
 */
public class AICreaterModClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        System.out.println("[AICreater] 正在初始化客户端渲染与交互界面...");

        // 1. 注册伴侣小狗实体渲染器（附带头顶心声气泡渲染）
        EntityRendererRegistry.register(AICreaterMod.COMPANION_DOG, CompanionDogRenderer::new);

        // 2. 注册服务端通知打开小狗专属聊天界面数据包接收器
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.S2C_OPEN_PET_SCREEN, (client, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            String dogName = buf.readString();
            int affection = buf.readInt();

            client.execute(() -> {
                client.setScreen(new PetChatScreen(entityId, dogName, affection));
            });
        });

        // 3. 注册接收小狗 DeepSeek 回复数据包
        ClientPlayNetworking.registerGlobalReceiver(ModPackets.S2C_PET_RESPONSE, (client, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            String reply = buf.readString();

            client.execute(() -> {
                if (client.currentScreen instanceof PetChatScreen chatScreen) {
                    chatScreen.onReceiveReply(reply);
                }
            });
        });

        System.out.println("[AICreater] 客户端初始化完毕！");
    }
}
