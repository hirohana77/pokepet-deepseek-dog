package com.aicreater.client.renderer;

import com.aicreater.entity.CompanionDogEntity;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.MobEntityRenderer;
import net.minecraft.client.render.entity.model.EntityModelLayers;
import net.minecraft.client.render.entity.model.WolfEntityModel;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;

/**
 * 伴侣小狗实体渲染器（采用专属中华田园大黄犬贴图，附带头顶浮空内心想法气泡渲染）
 */
public class CompanionDogRenderer extends MobEntityRenderer<CompanionDogEntity, WolfEntityModel<CompanionDogEntity>> {
    // 专属中华田园犬（大黄狗）定制贴图
    private static final Identifier CHINESE_RURAL_DOG_TEXTURE = new Identifier("aicreater", "textures/entity/companion_dog.png");

    public CompanionDogRenderer(EntityRendererFactory.Context context) {
        super(context, new WolfEntityModel<>(context.getPart(EntityModelLayers.WOLF)), 0.5F);
    }

    @Override
    public Identifier getTexture(CompanionDogEntity entity) {
        return CHINESE_RURAL_DOG_TEXTURE;
    }

    @Override
    public void render(CompanionDogEntity dog, float yaw, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        super.render(dog, yaw, tickDelta, matrices, vertexConsumers, light);

        // 渲染头顶拟人化内心想法（浮空心声气泡）
        if (dog.isShowingThought()) {
            String thought = "💭 " + dog.getThoughtText();
            renderThoughtBubble(dog, thought, matrices, vertexConsumers, light);
        }
    }

    private void renderThoughtBubble(CompanionDogEntity dog, String text, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light) {
        double d = this.dispatcher.getSquaredDistanceToCamera(dog);
        if (d > 4096.0D) return;

        matrices.push();
        matrices.translate(0.0D, dog.getHeight() + 0.45D, 0.0D);
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
