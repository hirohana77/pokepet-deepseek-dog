package com.aicreater.item;

import com.aicreater.AICreaterMod;
import com.aicreater.entity.CompanionDogEntity;
import com.aicreater.network.ModPackets;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.client.item.TooltipContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;

/**
 * 宝可梦风格宠物收纳球
 * 满球：全场景抛物线释放小狗
 * 空球：右键小狗收回，右键任意地方呼出【宠物专属技能指令菜单】
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

        // 无论先前状态如何，如果该小狗尚未绑定主人，手持宠物球第一时间直接认主
        if (!dog.isTamed() || dog.getOwnerUuid() == null) {
            dog.setOwner(user);
            dog.setTamed(true);
        } else if (!dog.isOwner(user)) {
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
     * 空白处直接右键：
     * 1. 若为满球：全场景触发“抛出 -> 空中顶点爆开召唤 -> 抛物线飘逸落地”
     * 2. 若为空球：搜索周围已释放的小狗，直接呼出【专属宠物技能菜单】！
     */
    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
        ItemStack stack = user.getStackInHand(hand);

        if (hasPet(stack)) {
            if (!world.isClient && world instanceof ServerWorld serverWorld) {
                executeArcRelease(serverWorld, stack, user);
            }
            return TypedActionResult.success(stack, world.isClient());
        } else {
            // 空球右键：呼出宠物技能指令菜单
            if (!world.isClient && user instanceof ServerPlayerEntity serverPlayer) {
                openSkillMenuForNearbyPet(world, serverPlayer);
            }
            return TypedActionResult.success(stack, world.isClient());
        }
    }

    /**
     * 对方块右键：同样逻辑统一
     */
    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        World world = context.getWorld();
        ItemStack stack = context.getStack();
        PlayerEntity user = context.getPlayer();

        if (hasPet(stack)) {
            if (!world.isClient && world instanceof ServerWorld serverWorld && user != null) {
                executeArcRelease(serverWorld, stack, user);
                return ActionResult.SUCCESS;
            }
            return ActionResult.SUCCESS;
        } else {
            if (!world.isClient && user instanceof ServerPlayerEntity serverPlayer) {
                openSkillMenuForNearbyPet(world, serverPlayer);
                return ActionResult.SUCCESS;
            }
        }

        return ActionResult.PASS;
    }

    /**
     * 寻找周围 18 格内属于玩家的小狗并呼出技能菜单
     */
    private void openSkillMenuForNearbyPet(World world, ServerPlayerEntity player) {
        Box searchBox = player.getBoundingBox().expand(18.0D, 10.0D, 18.0D);
        List<CompanionDogEntity> dogs = world.getEntitiesByClass(CompanionDogEntity.class, searchBox, d -> d.isOwner(player) && d.isAlive());

        if (dogs.isEmpty()) {
            player.sendMessage(Text.literal("§e🐾 周围 18 格内未找到属于你的伴侣小狗（需在附近且处于释放状态）"), true);
            return;
        }

        // 优先选择距离玩家最近的小狗
        dogs.sort(Comparator.comparingDouble(d -> d.squaredDistanceTo(player)));
        CompanionDogEntity targetDog = dogs.get(0);

        PacketByteBuf buf = PacketByteBufs.create();
        buf.writeInt(targetDog.getId());
        buf.writeString(targetDog.getName().getString());
        buf.writeBoolean(targetDog.isFlyingMode());
        buf.writeFloat(targetDog.getScaleFactor());
        buf.writeInt(targetDog.getAffection());

        ServerPlayNetworking.send(player, ModPackets.S2C_OPEN_SKILL_MENU, buf);
    }

    /**
     * 全局统一的宝可梦抛物线弧线召唤逻辑
     */
    private void executeArcRelease(ServerWorld world, ItemStack stack, PlayerEntity user) {
        NbtCompound nbt = stack.getNbt();
        if (nbt == null || !nbt.contains(NBT_PET_DATA)) return;

        Vec3d look = user.getRotationVec(1.0F);

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

        double apexDist = 3.2D;
        double apexHeight = Math.max(1.4D, 1.2D + (look.y > 0 ? look.y * 3.0D : 0.2D));
        double apexX = user.getX() + horizX * apexDist;
        double apexY = user.getEyeY() + apexHeight;
        double apexZ = user.getZ() + horizZ * apexDist;

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

        world.spawnParticles(ParticleTypes.FLASH, apexX, apexY + 0.2D, apexZ, 1, 0, 0, 0, 0);
        world.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, apexX, apexY, apexZ, 60, 0.5D, 0.5D, 0.5D, 0.35D);
        world.spawnParticles(ParticleTypes.FIREWORK, apexX, apexY, apexZ, 35, 0.4D, 0.4D, 0.4D, 0.25D);
        world.playSound(null, apexX, apexY, apexZ, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, SoundCategory.PLAYERS, 1.2F, 1.1F);
        world.playSound(null, apexX, apexY, apexZ, SoundEvents.ENTITY_EVOKER_CAST_SPELL, SoundCategory.PLAYERS, 1.0F, 1.6F);

        NbtCompound petData = nbt.getCompound(NBT_PET_DATA);
        CompanionDogEntity dog = new CompanionDogEntity(AICreaterMod.COMPANION_DOG, world);
        dog.readNbt(petData);
        dog.refreshPositionAndAngles(apexX, apexY, apexZ, user.getYaw(), 0.0F);

        dog.setOwner(user);
        dog.setTamed(true);
        dog.setSitting(false);
        dog.setInSittingPose(false);
        dog.setFlyingMode(false);
        dog.setNoGravity(false);
        dog.fallDistance = 0.0F;
        dog.setOnGround(false);
        dog.setThought("哇！飞出来啦！汪汪~", 140);

        dog.setVelocity(horizX * 0.35D, 0.04D, horizZ * 0.35D);
        dog.velocityModified = true;

        world.spawnEntity(dog);

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
            tooltip.add(Text.literal("§e👉 右键任意位置（对空/对地）均可抛球飞跃召唤"));
        } else {
            tooltip.add(Text.literal("§7[空收纳球]"));
            tooltip.add(Text.literal("§b👉 手持此球对着你的小狗右键即可收纳"));
            tooltip.add(Text.literal("§6👉 释放小狗后，手持此球右键任意地方即可打开【宠物技能菜单】"));
        }
        super.appendTooltip(stack, world, tooltip, context);
    }
}
