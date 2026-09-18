package com.aicreater.entity;

import com.aicreater.ai.DeepSeekService;
import com.aicreater.config.ModConfig;
import com.aicreater.item.PetBallItem;
import com.aicreater.network.ModPackets;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 伴侣型中华田园犬实体
 * 包含：自然行进朝向解耦（前行时面向前方随行、驻足时回头注视）、直升机尾巴真实旋转、巨犬倍化抗卡窒息保护、杂技后空翻与全景具身感知
 */
public class CompanionDogEntity extends WolfEntity {
    private static final TrackedData<String> THOUGHT_TEXT = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Integer> THOUGHT_TICKS = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> AFFECTION_LEVEL = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private static final TrackedData<Boolean> FLYING_MODE = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Float> SCALE_FACTOR = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Integer> BACKFLIP_TICKS = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.INTEGER);

    private int regularTickCounter = 0;
    private int globalBubbleCooldown = 0;
    private int emergencyEventCooldown = 0;
    private int envEventCooldown = 0;

    private boolean lastOwnerLowHealth = false;
    private boolean lastOwnerOnFire = false;
    private boolean lastCreeperNearby = false;
    private boolean lastInDeepCave = false;
    private boolean lastInNether = false;
    private boolean lastThundering = false;

    public CompanionDogEntity(EntityType<? extends WolfEntity> entityType, World world) {
        super(entityType, world);
        this.setSitting(false);
        this.setInSittingPose(false);
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(THOUGHT_TEXT, "汪呜~");
        this.dataTracker.startTracking(THOUGHT_TICKS, 0);
        this.dataTracker.startTracking(AFFECTION_LEVEL, 100);

        this.dataTracker.startTracking(FLYING_MODE, false);
        this.dataTracker.startTracking(SCALE_FACTOR, 1.0F);
        this.dataTracker.startTracking(BACKFLIP_TICKS, 0);
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
    public EntityDimensions getDimensions(EntityPose pose) {
        float scale = this.getScaleFactor();
        return super.getDimensions(pose).scaled(scale);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.FALL)) {
            return false;
        }
        return super.damage(source, amount);
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        return false;
    }

    @Override
    public void writeCustomDataToNbt(net.minecraft.nbt.NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (this.getOwnerUuid() != null) {
            nbt.putUuid("Owner", this.getOwnerUuid());
            nbt.putBoolean("IsTamed", true);
        }
        nbt.putInt("AffectionLevel", this.getAffection());
        nbt.putFloat("ScaleFactor", this.getScaleFactor());
        nbt.putBoolean("FlyingMode", this.isFlyingMode());
    }

    @Override
    public void readCustomDataFromNbt(net.minecraft.nbt.NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.containsUuid("Owner")) {
            this.setOwnerUuid(nbt.getUuid("Owner"));
            this.setTamed(true);
        } else if (nbt.contains("Owner")) {
            try {
                this.setOwnerUuid(nbt.getUuid("Owner"));
                this.setTamed(true);
            } catch (Exception ignored) {}
        }
        if (nbt.contains("AffectionLevel")) {
            this.dataTracker.set(AFFECTION_LEVEL, nbt.getInt("AffectionLevel"));
        }
        if (nbt.contains("ScaleFactor")) {
            this.setScaleFactor(nbt.getFloat("ScaleFactor"));
        }
        boolean flying = nbt.getBoolean("FlyingMode");
        this.setFlyingMode(flying);
        this.setNoGravity(flying);
    }

    public boolean isFlyingMode() {
        return this.dataTracker.get(FLYING_MODE);
    }

    public void setFlyingMode(boolean flying) {
        this.dataTracker.set(FLYING_MODE, flying);
        this.setNoGravity(flying);
        if (flying) {
            this.setSitting(false);
            this.setInSittingPose(false);
        }
    }

    public float getScaleFactor() {
        return this.dataTracker.get(SCALE_FACTOR);
    }

    public void setScaleFactor(float scale) {
        this.dataTracker.set(SCALE_FACTOR, scale);
        this.calculateDimensions();
    }

    public int getBackflipTicks() {
        return this.dataTracker.get(BACKFLIP_TICKS);
    }

    public void setBackflipTicks(int ticks) {
        this.dataTracker.set(BACKFLIP_TICKS, ticks);
    }

    @Override
    public float getTailAngle() {
        if (isFlyingMode()) {
            return 1.45F;
        }
        float baseAngle = super.getTailAngle();
        if (!this.isInSittingPose() && this.getOwner() != null) {
            float wag = MathHelper.cos(this.age * 0.45F) * 0.22F;
            return baseAngle + wag;
        }
        return baseAngle;
    }

    @Override
    public void travel(Vec3d movementInput) {
        if (this.isFlyingMode() && this.canMoveVoluntarily()) {
            this.updateVelocity(0.08F, movementInput);
            this.move(net.minecraft.entity.MovementType.SELF, this.getVelocity());
            this.setVelocity(this.getVelocity().multiply(0.85D));
            return;
        }
        super.travel(movementInput);
    }

    @Override
    public void tick() {
        super.tick();

        int flip = this.getBackflipTicks();
        if (flip > 0) {
            this.setBackflipTicks(flip - 1);
        }

        // 飞行巡航核心
        if (this.isFlyingMode()) {
            World world = this.getWorld();
            LivingEntity owner = this.getOwner();

            // 1. 柔和气流旋风粒子
            if (this.age % 2 == 0 && world instanceof ServerWorld serverWorld) {
                double tailX = this.getX() - Math.sin(this.getYaw() * Math.PI / 180.0D) * 0.45D;
                double tailZ = this.getZ() + Math.cos(this.getYaw() * Math.PI / 180.0D) * 0.45D;
                double tailY = this.getY() + 0.45D * this.getScaleFactor();
                serverWorld.spawnParticles(ParticleTypes.CLOUD, tailX, tailY, tailZ, 2, 0.08D, 0.05D, 0.08D, 0.02D);
                serverWorld.spawnParticles(ParticleTypes.SWEEP_ATTACK, tailX, tailY, tailZ, 1, 0, 0, 0, 0);
            }

            // 2. 舒缓柔和的微风滑翔气流声
            if (this.age % 22 == 0) {
                world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ITEM_ELYTRA_FLYING, SoundCategory.NEUTRAL, 0.18F, 1.35F);
            }

            // 3. 飞行跟随：地面逻辑完全保持不变，飞行时严格与角色保持 2.0 个方块的距离
            if (!world.isClient && owner != null) {
                float ownerYawRad = (float) (owner.getYaw() * Math.PI / 180.0D);
                // 空间位置：位于角色侧边保持精准 2.0 格距离，高度在肩膀上方 (Y + 1.2)
                double offsetX = -Math.sin(ownerYawRad - 0.45F) * 2.0D;
                double offsetZ = Math.cos(ownerYawRad - 0.45F) * 2.0D;
                double targetX = owner.getX() + offsetX;
                double targetZ = owner.getZ() + offsetZ;
                double targetY = owner.getY() + 1.2D;

                Vec3d toTarget = new Vec3d(targetX - this.getX(), targetY - this.getY(), targetZ - this.getZ());
                double dist = toTarget.length();

                if (dist > 0.3D) {
                    Vec3d flyVel = toTarget.normalize().multiply(Math.min(0.35D, dist * 0.16D));
                    this.setVelocity(this.getVelocity().multiply(0.62D).add(flyVel));
                    this.velocityModified = true;
                }

                // 飞行悬停时平滑注视角色
                this.getLookControl().lookAt(owner, 25.0F, 25.0F);
            }
        }

        if (!this.getWorld().isClient) {
            int currentTicks = this.dataTracker.get(THOUGHT_TICKS);
            if (currentTicks > 0) {
                this.dataTracker.set(THOUGHT_TICKS, currentTicks - 1);
            }

            if (globalBubbleCooldown > 0) globalBubbleCooldown--;
            if (emergencyEventCooldown > 0) emergencyEventCooldown--;
            if (envEventCooldown > 0) envEventCooldown--;

            if (this.age % 10 == 0) {
                checkStateTransitionAndTrigger();
            }

            regularTickCounter++;
            int regularInterval = Math.max(20, ModConfig.get().mindIntervalSeconds) * 20;
            if (regularTickCounter >= regularInterval) {
                regularTickCounter = 0;
                if (globalBubbleCooldown <= 0 && currentTicks <= 0) {
                    triggerEnvironmentThought();
                }
            }
        }
    }

    public void executePetSkill(int skillId, PlayerEntity player) {
        World world = this.getWorld();
        if (world.isClient) return;

        if (skillId == 1) {
            boolean nextFly = !this.isFlyingMode();
            this.setFlyingMode(nextFly);

            if (nextFly) {
                this.setVelocity(0.0D, 0.45D, 0.0D);
                this.velocityModified = true;
                if (world instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.EXPLOSION, this.getX(), this.getY(), this.getZ(), 1, 0, 0, 0, 0);
                    sw.spawnParticles(ParticleTypes.CLOUD, this.getX(), this.getY(), this.getZ(), 20, 0.4D, 0.2D, 0.4D, 0.05D);
                }
                world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_FIREWORK_ROCKET_LAUNCH, SoundCategory.PLAYERS, 1.0F, 1.2F);
                this.setThought("🚁 螺旋尾巴启动！我飞起来啦~", 140);
                player.sendMessage(Text.literal("§e✨ 伴侣小狗开启了【直升机尾巴空中飞行巡航】模式！"), true);
            } else {
                world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_WOLF_WHINE, SoundCategory.PLAYERS, 1.0F, 1.1F);
                this.setThought("缓缓降落回到地面啦~汪！", 100);
                player.sendMessage(Text.literal("§a✨ 伴侣小狗已平稳降落回到地面。"), true);
            }
        } else if (skillId == 2) {
            boolean isGiant = this.getScaleFactor() > 1.5F;
            if (!isGiant) {
                Box futureBox = this.getBoundingBox().expand(1.2D, 1.5D, 1.2D);
                if (world.getBlockCollisions(this, futureBox).iterator().hasNext()) {
                    this.refreshPositionAndAngles(player.getX(), player.getY(), player.getZ(), this.getYaw(), 0.0F);
                }
                this.setScaleFactor(2.6F);
                if (world instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.POOF, this.getX(), this.getY() + 1.0D, this.getZ(), 40, 0.6D, 0.6D, 0.6D, 0.1D);
                    sw.spawnParticles(ParticleTypes.TOTEM_OF_UNDYING, this.getX(), this.getY() + 1.0D, this.getZ(), 35, 0.5D, 0.5D, 0.5D, 0.2D);
                }
                world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_RAVAGER_ROAR, SoundCategory.PLAYERS, 0.7F, 1.4F);
                this.setThought("吼呜！巨型大黄登场！威武霸气！", 140);
                player.sendMessage(Text.literal("§6⚡ 伴侣小狗激活了【巨犬倍化术】（2.6倍体型，免疫窒息）！"), true);
            } else {
                this.setScaleFactor(1.0F);
                if (world instanceof ServerWorld sw) {
                    sw.spawnParticles(ParticleTypes.POOF, this.getX(), this.getY() + 0.5D, this.getZ(), 20, 0.3D, 0.3D, 0.3D, 0.05D);
                }
                world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_WOLF_SHAKE, SoundCategory.PLAYERS, 1.0F, 1.2F);
                this.setThought("变回小巧可爱形态啦~汪！", 100);
                player.sendMessage(Text.literal("§a✨ 伴侣小狗恢复为小巧可爱常态。"), true);
            }
        } else if (skillId == 3) {
            this.setBackflipTicks(16);
            this.setVelocity(0.0D, 0.44D, 0.0D);
            this.velocityModified = true;

            if (world instanceof ServerWorld sw) {
                sw.spawnParticles(ParticleTypes.FIREWORK, this.getX(), this.getY() + 0.5D, this.getZ(), 25, 0.3D, 0.3D, 0.3D, 0.15D);
                sw.spawnParticles(ParticleTypes.WAX_ON, this.getX(), this.getY() + 0.5D, this.getZ(), 15, 0.2D, 0.2D, 0.2D, 0.1D);
            }
            world.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.8F, 1.9F);
            this.addAffection(5);
            this.setThought("看我招牌后空翻！帅吧主人~", 120);
            player.sendMessage(Text.literal("§d🤸 伴侣小狗表演了【360°炫酷杂技后空翻】，好感度 +5！"), true);
        }
    }

    private void checkStateTransitionAndTrigger() {
        LivingEntity owner = this.getOwner();
        World world = this.getWorld();

        if (emergencyEventCooldown <= 0 && owner instanceof PlayerEntity player) {
            Box box = this.getBoundingBox().expand(8.0D, 4.0D, 8.0D);
            List<CreeperEntity> creepers = world.getEntitiesByClass(CreeperEntity.class, box, e -> e.isAlive());
            boolean hasCreeper = !creepers.isEmpty();
            if (hasCreeper && !lastCreeperNearby) {
                lastCreeperNearby = true;
                popReflexBubble("⚠️ 嘶嘶...有苦力怕！主人快闪开！", 140, SoundEvents.ENTITY_WOLF_GROWL, 25 * 20);
                return;
            }
            if (!hasCreeper) lastCreeperNearby = false;

            boolean onFire = player.isOnFire();
            if (onFire && !lastOwnerOnFire) {
                lastOwnerOnFire = true;
                popReflexBubble("🔥 哎呀着火啦！快跳水里呀主人！", 120, SoundEvents.ENTITY_WOLF_WHINE, 20 * 20);
                return;
            }
            if (!onFire) lastOwnerOnFire = false;

            boolean lowHp = player.getHealth() <= 6.0F && player.getHealth() > 0;
            if (lowHp && !lastOwnerLowHealth) {
                lastOwnerLowHealth = true;
                popReflexBubble("🩸 主人流了好多血！呜呜快吃点东西！", 140, SoundEvents.ENTITY_WOLF_WHINE, 30 * 20);
                return;
            }
            if (!lowHp) lastOwnerLowHealth = false;
        }

        if (envEventCooldown <= 0 && globalBubbleCooldown <= 0) {
            boolean inNether = world.getRegistryKey().getValue().getPath().equals("the_nether");
            if (inNether && !lastInNether) {
                lastInNether = true;
                popReflexBubble("这里好热到处是岩浆……旺财害怕~", 140, SoundEvents.ENTITY_WOLF_WHINE, 45 * 20);
                return;
            }
            if (!inNether) lastInNether = false;

            boolean deepCave = this.getBlockY() < 0;
            if (deepCave && !lastInDeepCave) {
                lastInDeepCave = true;
                popReflexBubble("好黑好深的地底呀，这里有钻石吗？汪~", 140, SoundEvents.ENTITY_WOLF_PANT, 40 * 20);
                return;
            }
            if (!deepCave) lastInDeepCave = false;

            boolean thundering = world.isThundering();
            if (thundering && !lastThundering) {
                lastThundering = true;
                popReflexBubble("⚡ 打雷闪电啦！吓得耳朵都缩起来了！", 140, SoundEvents.ENTITY_WOLF_WHINE, 40 * 20);
                return;
            }
            if (!thundering) lastThundering = false;
        }
    }

    private void popReflexBubble(String text, int showTicks, net.minecraft.sound.SoundEvent sound, int specificCooldown) {
        this.setThought(text, showTicks);
        this.globalBubbleCooldown = 16 * 20;
        this.emergencyEventCooldown = specificCooldown;
        this.envEventCooldown = specificCooldown;
        if (sound != null) {
            this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(), sound, SoundCategory.NEUTRAL, 1.0F, 1.1F);
        }
    }

    public String collectMicroContext() {
        World world = this.getWorld();
        String dim = world.getRegistryKey().getValue().getPath().equals("overworld") ? "主世界" : "异次元";
        String weather = world.isRaining() ? "下雨" : "晴朗";
        String depth = this.getBlockY() < 0 ? "深层矿洞" : "地表";
        LivingEntity owner = this.getOwner();
        String hpStatus = (owner != null && owner.getHealth() <= 6.0F) ? "主人危险残血" : "主人健康";
        return dim + ", " + weather + ", " + depth + ", " + hpStatus;
    }

    public void triggerEnvironmentThought() {
        if (this.getWorld().isClient) return;

        String microState = collectMicroContext();
        DeepSeekService.generateMindThoughtStateless(microState).thenAccept(thought -> {
            if (thought != null && !thought.isEmpty()) {
                this.setThought(thought, 140);
                this.globalBubbleCooldown = 18 * 20;
            }
        });
    }

    public String collectRealTimeState() {
        StringBuilder sb = new StringBuilder();

        LivingEntity owner = this.getOwner();
        if (owner instanceof PlayerEntity player) {
            float hp = player.getHealth();
            float maxHp = player.getMaxHealth();
            sb.append("【主人状态】姓名: ").append(player.getName().getString());
            sb.append(String.format(", 生命: %.1f/%.1f", hp, maxHp));
            if (hp <= 6.0F) {
                sb.append("(严重残血危险！需要医疗)");
            } else if (hp < 14.0F) {
                sb.append("(受了轻伤)");
            }

            int food = player.getHungerManager().getFoodLevel();
            sb.append(", 饥饿度: ").append(food).append("/20");
            if (food <= 6) {
                sb.append("(肚子咕咕叫，饥饿)");
            }

            ItemStack mainHand = player.getMainHandStack();
            sb.append(", 手持物: ").append(mainHand.isEmpty() ? "双手空空" : mainHand.getName().getString());

            List<String> actions = new ArrayList<>();
            if (player.isSneaking()) actions.add("悄悄蹲伏潜行");
            if (player.isSprinting()) actions.add("疾速奔跑冲刺");
            if (player.isTouchingWater()) actions.add("在水中游弋");
            if (player.isOnFire()) actions.add("身上着火了！");
            if (!actions.isEmpty()) {
                sb.append(", 动作行为: ").append(String.join("/", actions));
            }
            sb.append("\n");
        }

        World world = this.getWorld();
        sb.append("【世界时空】");
        String dimension = world.getRegistryKey().getValue().getPath();
        sb.append("维度: ").append(dimension.equals("overworld") ? "主世界" : (dimension.equals("the_nether") ? "地狱下界" : "末地"));

        if (world.isThundering()) {
            sb.append(", 天气: 电闪雷鸣雷暴大雨！");
        } else if (world.isRaining()) {
            sb.append(", 天气: 阴雨绵绵");
        } else {
            sb.append(", 天气: 天空晴朗");
        }

        long timeOfDay = world.getTimeOfDay() % 24000;
        if (timeOfDay < 1000) sb.append(", 时辰: 清晨日出");
        else if (timeOfDay < 6000) sb.append(", 时辰: 上午明媚");
        else if (timeOfDay < 12000) sb.append(", 时辰: 正午烈日");
        else if (timeOfDay < 13500) sb.append(", 时辰: 黄昏晚霞");
        else sb.append(", 时辰: 深夜寂静");

        int y = this.getBlockY();
        if (y < 0) {
            sb.append(", 位置: 极深幽暗地下矿洞(Y=").append(y).append(")");
        } else if (y > 110) {
            sb.append(", 位置: 崇山峻岭之巅(Y=").append(y).append(")");
        } else {
            sb.append(", 位置: 地表常界(Y=").append(y).append(")");
        }

        int light = world.getLightLevel(LightType.BLOCK, this.getBlockPos());
        if (light < 4 && !world.isDay()) {
            sb.append(", 处于昏暗危险区域(易刷怪)");
        }
        sb.append("\n");

        Box radarBox = this.getBoundingBox().expand(12.0D, 6.0D, 12.0D);
        List<Entity> nearbyEntities = world.getOtherEntities(this, radarBox);

        List<String> threats = new ArrayList<>();
        List<String> friends = new ArrayList<>();
        boolean foundCreeper = false;

        for (Entity e : nearbyEntities) {
            if (e instanceof CreeperEntity) {
                threats.add("苦力怕(极度危险随时自爆！)");
                foundCreeper = true;
            } else if (e instanceof Monster) {
                threats.add(e.getName().getString());
            } else if (e instanceof AnimalEntity && !(e instanceof CompanionDogEntity)) {
                friends.add(e.getName().getString());
            }
        }

        sb.append("【小狗嗅觉雷达】");
        if (foundCreeper) {
            sb.append("⚠️ 警报：闻到了火药味！附近潜伏着苦力怕！");
        }
        if (!threats.isEmpty()) {
            sb.append("威胁敌人: ").append(String.join(", ", threats.subList(0, Math.min(3, threats.size()))));
        } else {
            sb.append("周围很安全，没有危险怪物");
        }
        if (!friends.isEmpty()) {
            sb.append("; 附近可爱动物: ").append(String.join(", ", friends.subList(0, Math.min(3, friends.size()))));
        }
        sb.append("; 小狗好感度: ").append(this.getAffection()).append("/200 (状态: ").append(this.isInSittingPose() ? "坐下小憩" : "跟随探险").append(")");

        return sb.toString();
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

        // 0. 优先处理宠物收纳球！
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
                buf.writeUuid(this.getUuid());
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
