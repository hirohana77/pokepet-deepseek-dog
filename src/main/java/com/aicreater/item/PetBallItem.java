package com.aicreater.item;

import com.aicreater.AICreaterMod;
import com.aicreater.entity.CompanionDogEntity;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 宝可梦风格宠物收纳球（支持对狗收纳，所有角度释放均呈现统一经典的“抛出 -> 空中爆开召唤 -> 抛物线飘逸落地”全套弧线效果）
 */
public class PetBallItem extends Item {
    public static final String NBT_PET_DATA = "PetData";
    public static final String NBT_HAS_PET = "HasPet";

    public PetBallItem(Settings settings) {
        super(settings);
    }

    public static boolean hasPet(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return nbt != null && nbt.getBoolean(NBT_HAS_PET);
    }

    @Override
    public boolean hasGlint(ItemStack stack) {
        return hasPet(stack);
    }

    /**
     * 对实体右键交互：回收小狗进入宠物球
     */
    @Override
    public ActionResult useOnEntity(ItemStack stack, PlayerEntity user, LivingEntity entity, Hand hand) {
        if (!(entity instanceof CompanionDogEntity dog)) {
            return ActionResult.PASS;
        }

        World world = user.getWorld();
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }

        if (!dog.isOwner(user)) {
            user.sendMessage(Text.literal("§c这只小狗不属于你，无法收纳！"), true);
            return ActionResult.FAIL;
        }

        if (hasPet(stack)) {
            user.sendMessage(Text.literal("§c这个宠物球里已经有一只宠物了！"), true);
            return ActionResult.FAIL;
        }

        // 宝可梦收球特效与音效
        if (world instanceof ServerWorld serverWorld) {
            serverWorld.spawnParticles(ParticleTypes.PORTAL, dog.getX(), dog.getY() + 0.5D, dog.getZ(), 45, 0.3D, 0.4D, 0.3D, 0.8D);
            serverWorld.spawnParticles(ParticleTypes.WITCH, dog.getX(), dog.getY() + 0.5D, dog.getZ(), 20, 0.2D, 0.2D, 0.2D, 0.1D);
            serverWorld.spawnParticles(ParticleTypes.HEART, dog.getX(), dog.getY() + 0.8D, dog.getZ(), 5, 0.2D, 0.2D, 0.2D, 0.0D);
        }
        world.playSound(null, dog.getX(), dog.getY(), dog.getZ(), SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 1.0F, 1.3F);
        world.playSound(null, dog.getX(), dog.getY(), dog.getZ(), SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8F, 1.6F);

        NbtCompound petData = new NbtCompound();
        dog.saveNbt(petData);

        String petName = dog.getName().getString();
        int affection = dog.getAffection();
        float health = dog.getHealth();

        dog.discard();

        // 原位状态更新
        if (stack.getCount() == 1) {
            NbtCompound stackNbt = stack.getOrCreateNbt();
            stackNbt.put(NBT_PET_DATA, petData);
            stackNbt.putBoolean(NBT_HAS_PET, true);
            stackNbt.putString("PetName", petName);
            stackNbt.putInt("PetAffection", affection);
            stackNbt.putFloat("PetHealth", health);
        } else {
            stack.decrement(1);
            ItemStack filledBall = new ItemStack(AICreaterMod.PET_BALL, 1);
            NbtCompound stackNbt = filledBall.getOrCreateNbt();
            stackNbt.put(NBT_PET_DATA, petData);
            stackNbt.putBoolean(NBT_HAS_PET, true);
            stackNbt.putString("PetName", petName);
            stackNbt.putInt("PetAffection", affection);
            stackNbt.putFloat("PetHealth", health);

            if (!user.getInventory().insertStack(filledBall)) {
                user.dropItem(filledBall, false);
            }
        }

        user.sendMessage(Text.literal("§6✨ 伴侣小狗 [" + petName + "] 已收进宠物球！"), true);
        return ActionResult.SUCCESS;
    }

    /**
     * 空白处直接右键：全场景统一触发“抛出 -> 空中爆开召唤 -> 抛物线降落”经典全套弧线动效
     */
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (hasPet(stack)) {
            if (!world.isClient && world instanceof ServerWorld serverWorld) {
                executeArcRelease(serverWorld, stack, user);
            }
            return TypedActionResult.success(stack, world.isClient());
        }

        return super.use(world, user, hand);
    }

    /**
     * 对方块右键：同样统一呈现出球抛物线弧线效果
     */
    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        ItemStack stack = context.getStack();
        PlayerEntity user = context.getPlayer();

        if (!hasPet(stack)) {
            return ActionResult.PASS;
        }

        if (!world.isClient && world instanceof ServerWorld serverWorld && user != null) {
            executeArcRelease(serverWorld, stack, user);
            return ActionResult.SUCCESS;
        }

        return ActionResult.SUCCESS;
    }

    /**
     * 全局统一的宝可梦抛物线弧线召唤逻辑：
     * 无论仰望天空还是平视前方，均在前方上空划出抛球星轨 -> 空中顶点爆发金光与烟火 -> 小狗破球而出顺应物理重力弧线滑翔降落
     */
    private void executeArcRelease(ServerWorld world, ItemStack stack, PlayerEntity user) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains(NBT_PET_DATA)) return;

        Vec3d look = user.getRotationVec(1.0F);

        // 计算水平朝向（保证哪怕平视也能稳稳形成向前上方的优美抛物线）
        double horizX = look.x;
        double horizZ = look.z;
        double horizLen = Math.sqrt(horizX * horizX + horizZ * horizZ);
        if (horizLen < 0.01D) {
            horizX = 0.0D;
            horizZ = 1.0D;
            horizLen = 1.0D;
        }
        horizX /= horizLen;
        horizZ /= horizLen;

        // 动态计算半空中的出球顶点（Apex）：
        // 距离玩家前方约 3.2 格，高度在视线上方约 1.5 ~ 2.5 格
        double apexDist = 3.2D;
        double apexHeight = Math.max(1.4D, 1.2D + (look.y > 0 ? look.y * 3.0D : 0.2D));
        double apexX = user.getX() + horizX * apexDist;
        double apexY = user.getEyeY() + apexHeight;
        double apexZ = user.getZ() + horizZ * apexDist;

        // 1. 抛出弧线流光轨迹：从玩家眼前沿抛物线向 Apex 顶点喷洒密集星轨粒子
        Vec3d eyePos = user.getEyePos();
        int steps = 15;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double px = eyePos.x + (apexX - eyePos.x) * t;
            double py = eyePos.y + (apexY - eyePos.y) * t + Math.sin(t * Math.PI) * 0.75D;
            double pz = eyePos.z + (apexZ - eyePos.z) * t;
            world.spawnParticles(ParticleTypes.CRIT, px, py, pz, 1, 0, 0, 0, 0);
            world.spawnParticles(ParticleTypes.FIREWORK, px, py, pz, 1, 0, 0, 0, 0.01D);
        }
        world.playSound(null, user.getX(), user.getY(), user.getZ(), SoundEvents.ENTITY_SNOWBALL_THROW, SoundCategory.PLAYERS, 0.9F, 1.2F);

        // 2. 空中顶点爆开召唤：爆发刺目强闪光 + 金色图腾光环 + 烟火爆破环
        world.spawnParticles(ParticleTypes.FLASH, apexX, apexY + 0.2D, apexZ, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, apexX, apexY, apexZ, 60, 0.5D, 0.5D, 0.5D, 0.35D);
        world.spawnParticles(ParticleTypes.FIREWORK, apexX, apexY, apexZ, 35, 0.4D, 0.4D, 0.4D, 0.25D);
        world.playSound(null, apexX, apexY, apexZ, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 1.2F, 1.1F);
        world.playSound(null, apexX, apexY, apexZ, SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 1.0F, 1.6F);

        // 3. 小狗破球诞生，赋予向前下方的抛物线动量（自然滑翔降落到地面）
        NbtCompound petData = nbt.getCompound(NBT_PET_DATA);
        CompanionDogEntity dog = new CompanionDogEntity(AICreaterMod.COMPANION_DOG, world);
        dog.readNbt(petData);
        dog.refreshPositionAndAngles(apexX, apexY, apexZ, user.getYaw(), 0.0F);

        dog.setOwner(user);
        dog.setTamed(true);
        dog.setSitting(false);
        dog.setInSittingPose(false);
        dog.setThought("哇！飞出来啦！汪汪~", 140);

        // 赋予向前水平速度与微向上初始冲量，在重力作用下呈现出极度平滑优雅的下坠落地抛物线
        dog.setVelocity(horizX * 0.38D, 0.12D, horizZ * 0.38D);
        dog.velocityModified = true;

        world.spawnEntity(dog);

        // 4. 手中宠物球原位恢复为空球
        nbt.remove(NBT_PET_DATA);
        nbt.putBoolean(NBT_HAS_PET, false);
        nbt.remove("PetName");
        nbt.remove("PetAffection");
        nbt.remove("PetHealth");

        user.sendMessage(Text.literal("§a✨ 去吧，" + dog.getName().getString() + "！"), true);
    }

    @Override
    public void appendTooltip(ItemStack stack, @Nullable World world, List<Text> tooltip, TooltipContext context) {
        if (hasPet(stack)) {
            NbtCompound nbt = stack.getNbt();
            String name = nbt != null && nbt.contains("PetName") ? nbt.getString("PetName") : "智能伴侣小狗";
            int affection = nbt != null && nbt.contains("PetAffection") ? nbt.getInt("PetAffection") : 100;
            float hp = nbt != null && nbt.contains("PetHealth") ? nbt.getFloat("PetHealth") : 20.0F;

            tooltip.add(Text.literal("§6🐾 已收纳宠物: §f" + name));
            tooltip.add(Text.literal("§d❤ 好感度: §f" + affection));
            tooltip.add(Text.literal(String.format("§a❤ 生命值: §f%.1f/20", hp)));
            tooltip.add(Text.literal("§e👉 右键即可抛出宠物球，体验飞跃召唤弧线！"));
        } else {
            tooltip.add(Text.literal("§7[空收纳球]"));
            tooltip.add(Text.literal("§b👉 手持此球对着你的小狗右键即可收纳"));
        }
        super.appendTooltip(stack, world, tooltip, context);
    }
}
