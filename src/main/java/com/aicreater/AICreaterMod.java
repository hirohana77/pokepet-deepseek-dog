package com.aicreater;

import com.aicreater.config.ModConfig;
import com.aicreater.entity.CompanionDogEntity;
import com.aicreater.item.PetBallItem;
import com.aicreater.network.ModPackets;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricEntityTypeBuilder;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * AI Creater 模组主类
 */
public class AICreaterMod implements ModInitializer {
    public static final String MOD_ID = "aicreater";

    // 1. 注册伴侣小狗实体类型
    public static final EntityType<CompanionDogEntity> COMPANION_DOG = Registry.register(
            Registries.ENTITY_TYPE,
            new Identifier(MOD_ID, "companion_dog"),
            FabricEntityTypeBuilder.create(SpawnGroup.CREATURE, CompanionDogEntity::new)
                    .dimensions(EntityDimensions.fixed(0.6F, 0.85F))
                    .build()
    );

    // 2. 注册小狗刷怪蛋物品
    public static final Item COMPANION_DOG_SPAWN_EGG = Registry.register(
            Registries.ITEM,
            new Identifier(MOD_ID, "companion_dog_spawn_egg"),
            new SpawnEggItem(COMPANION_DOG, 0xD7B38C, 0x8B5A2B, new FabricItemSettings())
    );

    // 3. 注册宝可梦风格宠物收纳球道具
    public static final Item PET_BALL = Registry.register(
            Registries.ITEM,
            new Identifier(MOD_ID, "pet_ball"),
            new PetBallItem(new FabricItemSettings().maxCount(16))
    );

    @Override
    public void onInitialize() {
        System.out.println("[AICreater] 正在初始化智能伴侣宠物模组 (中华田园犬与宠物收纳球系统)...");

        // 加载或生成本地配置文件
        ModConfig.load();

        // 注册实体基础属性
        FabricDefaultAttributeRegistry.register(COMPANION_DOG, WolfEntity.createWolfAttributes());

        // 注册物品栏展示
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.SPAWN_EGGS).register(entries -> {
            entries.add(COMPANION_DOG_SPAWN_EGG);
        });
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> {
            entries.add(PET_BALL);
        });

        // 注册网络通信接收端
        ModPackets.registerServerReceivers();

        System.out.println("[AICreater] 模组初始化完成！田园犬与宠物球已就绪。");
    }
}
