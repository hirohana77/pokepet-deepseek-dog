package com.aicreater.entity;

import com.aicreater.ai.DeepSeekService;
import com.aicreater.config.ModConfig;
import com.aicreater.item.PetBallItem;
import com.aicreater.network.ModPackets;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.*;
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
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * 伴侣型中华田园犬实体（双轨制情绪事件驱动引擎 + 智能防刷屏冷却系统）
 */
public class CompanionDogEntity extends WolfEntity {
    private static final TrackedData<String> THOUGHT_TEXT = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Integer> THOUGHT_TICKS = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Integer> AFFECTION_LEVEL = DataTracker.registerData(CompanionDogEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // 冷却与频率状态控制器
    private int regularTickCounter = 0;
    private int globalBubbleCooldown = 0;       // 全局气泡最小防刷间隔（避免接连冒泡）
    private int emergencyEventCooldown = 0;     // 危机警报冷却（防止同一危险连续触发）
    private int envEventCooldown = 0;           // 环境突变冷却

    // 历史状态跟踪器（用于捕获 0 -> 1 状态突变，避免持续状态高频刷屏）
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
            // 1. 气泡存活时间递减
            int currentTicks = this.dataTracker.get(THOUGHT_TICKS);
            if (currentTicks > 0) {
                this.dataTracker.set(THOUGHT_TICKS, currentTicks - 1);
            }

            // 2. 冷却计数器递减
            if (globalBubbleCooldown > 0) globalBubbleCooldown--;
            if (emergencyEventCooldown > 0) emergencyEventCooldown--;
            if (envEventCooldown > 0) envEventCooldown--;

            // 3. 实时事件感知扫描（每 10 ticks 检查一次环境与角色状态跃迁）
            if (this.age % 10 == 0) {
                checkStateTransitionAndTrigger();
            }

            // 4. 常态闲聊心流低频轮询（默认 45~60 秒周期）
            regularTickCounter++;
            int regularInterval = Math.max(20, ModConfig.get().mindIntervalSeconds) * 20;
            if (regularTickCounter >= regularInterval) {
                regularTickCounter = 0;
                // 仅在全局冷却完毕且当前没有冒泡时触发常态闲聊
                if (globalBubbleCooldown <= 0 && currentTicks <= 0) {
                    triggerEnvironmentThought();
                }
            }
        }
    }

    /**
     * 具身事件驱动：捕获世界与主人状态的突变（0 -> 1 跃迁），按优先级触发即时心声反应
     */
    private void checkStateTransitionAndTrigger() {
        LivingEntity owner = this.getOwner();
        World world = this.getWorld();

        // --- P0 级：致命危机事件（即时本能反应，高优先级） ---
        if (emergencyEventCooldown <= 0 && owner instanceof PlayerEntity player) {
            // (1) 苦力怕突发潜入警戒
            Box box = this.getBoundingBox().expand(8.0D, 4.0D, 8.0D);
            List<CreeperEntity> creepers = world.getEntitiesByClass(CreeperEntity.class, box, e -> e.isAlive());
            boolean hasCreeper = !creepers.isEmpty();
            if (hasCreeper && !lastCreeperNearby) {
                lastCreeperNearby = true;
                popReflexBubble("⚠️ 嘶嘶...有苦力怕！主人快闪开！", 140, SoundEvents.ENTITY_WOLF_GROWL, 25 * 20);
                return;
            }
            if (!hasCreeper) lastCreeperNearby = false;

            // (2) 主人身上着火
            boolean onFire = player.isOnFire();
            if (onFire && !lastOwnerOnFire) {
                lastOwnerOnFire = true;
                popReflexBubble("🔥 哎呀着火啦！快跳水里呀主人！", 120, SoundEvents.ENTITY_WOLF_WHINE, 20 * 20);
                return;
            }
            if (!onFire) lastOwnerOnFire = false;

            // (3) 主人生命值跌破残血危险线 (<= 6.0 点血量)
            boolean lowHp = player.getHealth() <= 6.0F && player.getHealth() > 0;
            if (lowHp && !lastOwnerLowHealth) {
                lastOwnerLowHealth = true;
                popReflexBubble("🩸 主人流了好多血！呜呜快吃点东西！", 140, SoundEvents.ENTITY_WOLF_WHINE, 30 * 20);
                return;
            }
            if (!lowHp) lastOwnerLowHealth = false;
        }

        // --- P1 级：重要环境与空间跃迁事件（冷却 35 秒） ---
        if (envEventCooldown <= 0 && globalBubbleCooldown <= 0) {
            // (1) 维度突变：进入下界地狱
            boolean inNether = world.getRegistryKey().getValue().getPath().equals("the_nether");
            if (inNether && !lastInNether) {
                lastInNether = true;
                popReflexBubble("这里好热到处是岩浆……旺财害怕~", 140, SoundEvents.ENTITY_WOLF_WHINE, 45 * 20);
                return;
            }
            if (!inNether) lastInNether = false;

            // (2) 深入地下深渊矿洞 (Y < 0)
            boolean deepCave = this.getBlockY() < 0;
            if (deepCave && !lastInDeepCave) {
                lastInDeepCave = true;
                popReflexBubble("好黑好深的地底呀，这里有钻石吗？汪~", 140, SoundEvents.ENTITY_WOLF_PANT, 40 * 20);
                return;
            }
            if (!deepCave) lastInDeepCave = false;

            // (3) 雷暴大雨突变
            boolean thundering = world.isThundering();
            if (thundering && !lastThundering) {
                lastThundering = true;
                popReflexBubble("⚡ 打雷闪电啦！吓得耳朵都缩起来了！", 140, SoundEvents.ENTITY_WOLF_WHINE, 40 * 20);
                return;
            }
            if (!thundering) lastThundering = false;
        }
    }

    /**
     * 弹出本能事件心声气泡（带音效、设置冷却防刷屏）
     */
    private void popReflexBubble(String text, int showTicks, net.minecraft.sound.SoundEvent sound, int specificCooldown) {
        this.setThought(text, showTicks);
        this.globalBubbleCooldown = 16 * 20; // 全局防刷保护：至少间隔 16 秒
        this.emergencyEventCooldown = specificCooldown;
        this.envEventCooldown = specificCooldown;
        if (sound != null) {
            this.getWorld().playSound(null, this.getX(), this.getY(), this.getZ(), sound, SoundCategory.NEUTRAL, 1.0F, 1.1F);
        }
    }

    /**
     * 全景多维具身感知采集器（收集角色、世界、天气与周围生物态势快照）
     */
    public String collectRealTimeState() {
        StringBuilder sb = new StringBuilder();

        // 1. 角色状态快照 (Player State)
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

        // 2. 世界时空与微环境 (World & Environment)
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

        // 3. 周围 12 格生物雷达 (Nearby Entity Radar)
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

    /**
     * 极轻量微感知快照（仅 20~30 字符，专用于头顶气泡心声，大幅减少 Prompt Input Token 开销）
     */
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

        // 使用极简微感知 + 无状态轻量通道，绝不污染对话记忆，Token 开销削减 85%
        String microState = collectMicroContext();
        DeepSeekService.generateMindThoughtStateless(microState).thenAccept(thought -> {
            if (thought != null && !thought.isEmpty()) {
                this.setThought(thought, 140);
                this.globalBubbleCooldown = 18 * 20; // 闲聊后进入 18 秒防刷冷却
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

        // 2. 潜行 + 空手：打开专属宠物聊天界面（传递持久化 UUID 绑定）
        if (player.isSneaking() && itemStack.isEmpty()) {
            if (player instanceof ServerPlayerEntity serverPlayer) {
                PacketByteBuf buf = PacketByteBufs.create();
                buf.writeUuid(this.getUuid()); // 写入小狗唯一持久化 UUID
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
