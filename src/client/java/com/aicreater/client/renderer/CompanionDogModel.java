package com.aicreater.client.renderer;

import com.aicreater.entity.CompanionDogEntity;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.model.WolfEntityModel;

/**
 * 伴侣小狗专属骨骼模型
 * 支持：直升机螺旋尾巴 360° 高速打转动画、坐卧与跑动姿态
 */
public class CompanionDogModel extends WolfEntityModel<CompanionDogEntity> {
    private final ModelPart tailPart;

    public CompanionDogModel(ModelPart root) {
        super(root);
        this.tailPart = root.getChild("tail");
    }

    @Override
    public void setAngles(CompanionDogEntity dog, float limbAngle, float limbDistance, float animationProgress, float headYaw, float headPitch) {
        super.setAngles(dog, limbAngle, limbDistance, animationProgress, headYaw, headPitch);

        // 当处于直升机飞行模式时，尾巴水平平举并以极高角速度 360° 疯狂转动（如同直升机螺旋桨）
        if (dog.isFlyingMode()) {
            this.tailPart.pitch = 1.45F; // 向后水平展开
            this.tailPart.roll = animationProgress * 1.8F; // 每一帧 360 度极速飞转！
            this.tailPart.yaw = 0.0F;
        }
    }
}
