package com.aicreater.entity;

import com.aicreater.ai.DeepSeekService;
import com.aicreater.config.ModConfig;
import com.aicreater.item.PetBallItem;
import com.aicreater.network.ModPackets;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/**
 * 伴侣型中华田园犬实体（灵动摇尾巴与拟人化交互）
 */
public class CompanionDogEntity extends WolfEntity {
    private static final TrackedData<String> THOUGHT_TEXT = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Integer> THOUGHT_TICKS = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> AFFECTION_LEVEL = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private int mindTickCounter = 0;

    public CompanionDogEntity(EntityType<? extends WolfEntity> entityType, World world) {
        super(entityType, world);
        // 默认站立状态
        this.setSitting(false);
        this.setInSittingPose(false);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(THOUGHT_TEXT, "汪呜~");
        this.dataTracker.startTracking(THOUGHT_TICKS, 0);
        this.dataTracker.startTracking(AFFECTION_LEVEL, 100);
    }

    @Override
    protected void initGoals() {
        super.initGoals();
        this.goalSelector.add(1, new SwimGoal(this));
        this.goalSelector.add(2, new SitGoal(this));
        this.goalSelector.add(4, new PounceAtTargetGoal(this, 0.4F));
        this.goalSelector.add(5, new MeleeAttackGoal(this, 1.0D, true));
        this.goalSelector.add(6, new FollowOwnerGoal(this, 1.25D, 5.0F, 2.0F, false));
        this.goalSelector.add(7, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(8, new LookAroundGoal(this));
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, net.minecraft.entity.damage.DamageSource damageSource) {
        // 伴侣小狗拥有灵动轻巧身姿，免除从半空抛掷降落时的跌落摔伤
        return false;
    }

    @Override
    public float getTailAngle() {
        float baseAngle = super.getTailAngle();
        if (!this.isInSittingPose() && this.getOwner() != null) {
            float wag = MathHelper.cos(this.age * 0.45F) * 0.22F;
            return baseAngle + wag;
        }
        return baseAngle;
    }

    @Override
    public void tick() {
        super.tick();

        if (!this.getWorld().isClient) {
            int currentTicks = this.dataTracker.get(THOUGHT_TICKS);
            if (currentTicks > 0) {
                this.dataTracker.set(THOUGHT_TICKS, currentTicks - 1);
            }

            // 定时感知环境并触发内心想法
            mindTickCounter++;
            int intervalTicks = Math.max(10, ModConfig.get().mindIntervalSeconds) * 20;
            if (mindTickCounter >= intervalTicks) {
                mindTickCounter = 0;
                triggerEnvironmentThought();
            }
        }
    }

    public void triggerEnvironmentThought() {
        if (this.getWorld().isClient) return;

        StringBuilder env = new StringBuilder();
        if (this.getOwner() != null) {
            float ownerHp = this.getOwner().getHealth();
            float ownerMaxHp = this.getOwner().getMaxHealth();
            env.append("主人血量: ").append(String.format("%.1f/%.1f", ownerHp, ownerMaxHp)).append("; ");
        }
        env.append("天气: ").append(this.getWorld().isRaining() ? "下雨" : "晴朗").append("; ");
        env.append("时间: ").append(this.getWorld().isDay() ? "白天" : "黑夜").append("; ");
        env.append("位置: ").append(this.getBlockPos().toShortString());

        DeepSeekService.generateMindThought(env.toString()).thenAccept(thought -> {
            if (thought != null && !thought.isEmpty()) {
                this.setThought(thought, 140);
            }
        });
    }

    public void setThought(String text, int ticks) {
        this.dataTracker.set(THOUGHT_TEXT, text);
        this.dataTracker.set(THOUGHT_TICKS, ticks);
    }

    public String getThoughtText() {
        return this.dataTracker.get(THOUGHT_TEXT);
    }

    public boolean isShowingThought() {
        return this.dataTracker.get(THOUGHT_TICKS) > 0;
    }

    public int getAffection() {
        return this.dataTracker.get(AFFECTION_LEVEL);
    }

    public void addAffection(int delta) {
        int val = Math.max(0, Math.min(200, getAffection() + delta));
        this.dataTracker.set(AFFECTION_LEVEL, val);
    }

    @Override
    public ActionResult interactMob(PlayerEntity player, Hand hand) {
        ItemStack itemStack = player.getStackInHand(hand);

        // 0. 优先处理宠物收纳球！绝不被后续坐下逻辑拦截
        if (itemStack.getItem() instanceof PetBallItem petBall) {
            return petBall.useOnEntity(itemStack, player, this, hand);
        }

        // 1. 投喂骨头或肉类：互动增加好感与回血
        if (itemStack.isOf(Items.BONE) || itemStack.isOf(Items.COOKED_BEEF) || itemStack.isOf(Items.COOKED_PORKCHOP)) {
            if (this.getWorld().isClient) {
                return ActionResult.SUCCESS;
            }
            if (!player.getAbilities().creativeMode) {
                itemStack.decrement(1);
            }
            this.heal(4.0F);
            this.addAffection(10);
            this.playSound(SoundEvents.ENTITY_GENERIC_EAT, 1.0F, 1.0F);
            this.getWorld().addParticle(ParticleTypes.HEART, this.getX(), this.getY() + 0.6D, this.getZ(), 0.0D, 0.2D, 0.0D);
            this.setThought("太好吃啦！谢谢主人！汪汪~", 100);
            return ActionResult.CONSUME;
        }

        if (this.getWorld().isClient) {
            return ActionResult.SUCCESS;
        }

        // 自动认主
        if (this.getOwner() == null) {
            this.setOwner(player);
            this.setTamed(true);
            this.getWorld().addParticle(ParticleTypes.HEART, this.getX(), this.getY() + 0.6D, this.getZ(), 0.0D, 0.2D, 0.0D);
        }

        // 2. 潜行 + 空手：打开专属宠物聊天界面
        if (player.isSneaking() && itemStack.isEmpty()) {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeInt(this.getId());
                buf.writeString(this.getName().getString());
                buf.writeInt(this.getAffection());
                ServerPlayNetworking.send(serverPlayer, ModPackets.S2C_OPEN_PET_SCREEN, buf);
            }
            return ActionResult.CONSUME;
        }

        // 3. 空手普通右键：切换坐立姿势
        if (itemStack.isEmpty()) {
            boolean nextSitting = !this.isSitting();
            this.setSitting(nextSitting);
            this.setInSittingPose(nextSitting);
            this.jumping = false;
            this.navigation.stop();
            this.setTarget(null);

            this.addAffection(1);
            this.playSound(SoundEvents.ENTITY_WOLF_WHINE, 1.0F, 1.2F);
            this.getWorld().addParticle(ParticleTypes.HEART, this.getX(), this.getY() + 0.6D, this.getZ(), 0.0D, 0.1D, 0.0D);

            if (nextSitting) {
                this.setThought("乖乖坐下等主人~", 80);
            } else {
                this.setThought("站起来啦，随时准备出发！汪！", 80);
            }
            return ActionResult.CONSUME;
        }

        return super.interactMob(player, hand);
    }
}
