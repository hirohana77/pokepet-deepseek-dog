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
 * 模组自定义网络数据包注册与处理（支持对话与宠物专属技能触发）
 */
public class ModPackets {
    public static final Identifier C2S_PET_CHAT = new Identifier("aicreater", "pet_chat");
    public static final Identifier S2C_OPEN_PET_SCREEN = new Identifier("aicreater", "open_pet_screen");
    public static final Identifier S2C_PET_RESPONSE = new Identifier("aicreater", "pet_response");

    // 专属宠物技能与配置网络通道
    public static final Identifier S2C_OPEN_SKILL_MENU = new Identifier("aicreater", "open_skill_menu");
    public static final Identifier C2S_TRIGGER_PET_SKILL = new Identifier("aicreater", "trigger_pet_skill");
    public static final Identifier C2S_UPDATE_CONFIG = new Identifier("aicreater", "update_config");

    public static void registerServerReceivers() {
        // 1. 服务端接收玩家在专属聊天框发送的信息
        ServerPlayNetworking.registerGlobalReceiver(C2S_PET_CHAT, (server, player, handler, buf, responseSender) -> {
            java.util.UUID dogUuid = buf.readUuid();
            int entityId = buf.readInt();
            String playerText = buf.readString(512);

            server.execute(() -> {
                Entity entity = player.getWorld().getEntityById(entityId);
                if (entity instanceof CompanionDogEntity dog) {
                    String realTimeState = dog.collectRealTimeState();

                    DeepSeekService.askDeepSeekChat(dog.getId(), playerText, realTimeState).thenAccept(reply -> {
                        server.execute(() -> {
                            dog.setThought(reply, 160);
                            dog.getWorld().playSound(null, dog.getX(), dog.getY(), dog.getZ(),
                                    SoundEvents.ENTITY_WOLF_AMBIENT, SoundCategory.NEUTRAL, 1.0F, 1.2F);

                            PacketByteBuf respBuf = PacketByteBufs.create();
                            respBuf.writeUuid(dogUuid);
                            respBuf.writeInt(entityId);
                            respBuf.writeString(reply);
                            ServerPlayNetworking.send(player, S2C_PET_RESPONSE, respBuf);
                        });
                    });
                }
            });
        });

        // 2. 服务端接收触发宠物技能请求
        ServerPlayNetworking.registerGlobalReceiver(C2S_TRIGGER_PET_SKILL, (server, player, handler, buf, responseSender) -> {
            int entityId = buf.readInt();
            int skillId = buf.readInt(); // 1: 螺旋起飞, 2: 变大变小, 3: 后空翻

            server.execute(() -> {
                Entity entity = player.getWorld().getEntityById(entityId);
                if (entity instanceof CompanionDogEntity dog && dog.isOwner(player)) {
                    dog.executePetSkill(skillId, player);
                }
            });
        });

        // 3. 服务端接收游戏内热配置更新请求
        ServerPlayNetworking.registerGlobalReceiver(C2S_UPDATE_CONFIG, (server, player, handler, buf, responseSender) -> {
            String key = buf.readString();
            String model = buf.readString();
            String url = buf.readString();

            server.execute(() -> {
                com.aicreater.config.ModConfig config = com.aicreater.config.ModConfig.get();
                config.apiKey = key;
                if (!model.isEmpty()) config.model = model;
                if (!url.isEmpty()) config.apiUrl = url;
                com.aicreater.config.ModConfig.save();
            });
        });
    }
}
