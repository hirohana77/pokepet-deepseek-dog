package com.aicreater.network;

import com.aicreater.ai.DeepSeekService;
import com.aicreater.entity.CompanionDogEntity;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

/**
 * 模组自定义网络数据包注册与处理
 */
public class ModPackets {
    public static final Identifier C2S_PET_CHAT = new Identifier("aicreater", "pet_chat");
    public static final Identifier S2C_OPEN_PET_SCREEN = new Identifier("aicreater", "open_pet_screen");
    public static final Identifier S2C_PET_RESPONSE = new Identifier("aicreater", "pet_response");

    public static void registerServerReceivers() {
        // 服务端接收玩家在专属聊天框发送的信息
        ServerPlayNetworking.registerGlobalReceiver(C2S_PET_CHAT, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            String playerText = buf.readString(512);

            server.execute(() -> {
                Entity entity = player.getWorld().getEntityById(entityId);
                if (entity instanceof CompanionDogEntity dog) {
                    // 组装当前游戏上下文
                    String context = String.format("玩家名字:%s, 玩家生命:%.1f, 天气:%s",
                            player.getName().getString(),
                            player.getHealth(),
                            dog.getWorld().isRaining() ? "下雨" : "晴天");

                    // 异步调用 DeepSeek
                    DeepSeekService.askDeepSeek(playerText, context).thenAccept(reply -> {
                        server.execute(() -> {
                            // 1. 同步设置小狗头顶心声
                            dog.setThought(reply, 160);
                            dog.getWorld().playSound(null, dog.getX(), dog.getY(), dog.getZ(),
                                    SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.NEUTRAL, 1.0F, 1.2F);

                            // 2. 发送响应包回客户端聊天界面
                            PacketByteBuf respBuf = PacketByteBufs.create();
                            respBuf.writeInt(entityId);
                            respBuf.writeString(reply);
                            ServerPlayNetworking.send(player, S2C_PET_RESPONSE, respBuf);
                        });
                    });
                }
            });
        });
    }
}
