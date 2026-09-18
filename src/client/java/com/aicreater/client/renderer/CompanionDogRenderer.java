package com.aicreater.client.renderer;

import com.aicreater.entity.CompanionDogEntity;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;

/**
 * 伴侣小狗实体渲染器
 * 使用 CompanionDogModel 驱动尾巴 360° 真实螺旋桨打转、体型放大与 360° 后空翻
 */
public class CompanionDogRenderer extends MobEntityRenderer<CompanionDogEntity, CompanionDogModel> {
    private static final Identifier CHINESE_RURAL_DOG_TEXTURE = new Identifier("aicreater", "textures/entity/companion_dog.png");

    public CompanionDogRenderer(EntityRendererFactory.Context context) {
        super(context, new CompanionDogModel(context.getPart(EntityModelLayers.WOLF)), 0.5F);
    }

    @Override
    public Identifier getTexture(CompanionDogEntity entity) {
        return CHINESE_RURAL_DOG_TEXTURE;
    }

    @Override
    public void render(CompanionDogEntity dog, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        matrices.push();

        // 1. 动态倍化术（渲染缩放）
        float scale = dog.getScaleFactor();
        if (scale != 1.0F) {
            matrices.scale(scale, scale, scale);
        }

        // 2. 杂技 360° 后空翻骨骼旋转特技动画
        int flipTicks = dog.getBackflipTicks();
        if (flipTicks > 0) {
            float progress = (16.0F - (flipTicks - tickDelta)) / 16.0F;
            progress = Math.max(0.0F, Math.min(1.0F, progress));

            matrices.translate(0.0D, 0.4D, 0.0D);
            matrices.multiply(RotationAxis.POSITIVE_X.rotationDegrees(progress * 360.0F));
            matrices.translate(0.0D, -0.4D, 0.0D);
        }

        super.render(dog, yaw, tickDelta, matrices, vertexConsumers, light);
        matrices.pop();

        // 3. 渲染头顶拟人化内心想法（浮空心声气泡）
        if (dog.isShowingThought()) {
            String thought = "💭 " + dog.getThoughtText();
            renderThoughtBubble(dog, thought, matrices, vertexConsumers, light, scale);
        }
    }

    private void renderThoughtBubble(CompanionDogEntity dog, String text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, float scale) {
        double d = this.dispatcher.getSquaredDistanceToCamera(dog);
        if (d > 4096.0D) return;

        matrices.push();
        matrices.translate(0.0D, (dog.getHeight() * scale) + 0.45D, 0.0D);
        matrices.multiply(this.dispatcher.getRotation());
        matrices.scale(-0.025F, -0.025F, 0.025F);

        Matrix4f matrix4f = matrices.peek().getPositionMatrix();
        TextRenderer textRenderer = this.getTextRenderer();
        float xOffset = (float) (-textRenderer.getWidth(text) / 2);

        int backgroundColor = (int) (0.7F * 255.0F) << 24 | 0x222233;
        textRenderer.draw(
                Text.literal(text),
                xOffset,
                0,
                0xFFFFAA,
                false,
                matrix4f,
                vertexConsumers,
                TextRenderer.TextLayerType.SEE_THROUGH,
                backgroundColor,
                light
        );

        matrices.pop();
    }
}
