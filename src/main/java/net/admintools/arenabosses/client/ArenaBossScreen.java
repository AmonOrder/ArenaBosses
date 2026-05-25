package net.admintools.arenabosses.client;

import com.google.gson.Gson;
import net.admintools.arenabosses.BossConfig;
import net.admintools.arenabosses.server.ModNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

public class ArenaBossScreen extends Screen {
    private final BossContainer container;

    public ArenaBossScreen(String jsonString) {
        super(Text.literal("Выбор Босса"));
        ModNetworking.MenuDataPacket data = new Gson().fromJson(jsonString, ModNetworking.MenuDataPacket.class);
        this.container = new BossContainer(data);
    }

    private static void drawBorder(DrawContext ctx, int x, int y, int w, int h, int color) {
        ctx.fill(x, y, x + w, y + 1, color);
        ctx.fill(x, y + h - 1, x + w, y + h, color);
        ctx.fill(x, y, x + 1, y + h, color);
        ctx.fill(x + w - 1, y, x + w, y + h, color);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xCC000000);
        // Текст заголовка
        Text title = Text.literal("МЕНЮ ПРИЗЫВА АРЕНЫ");
        int titleY = container.getY() - 25; // Попробуйте 25 или 30, чтобы подобрать идеальное расстояние
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, titleY, 0xFFFFFF);
        container.render(context, mouseX, mouseY);
    }

    @Override public boolean mouseClicked(double mx, double my, int b) { return container.mouseClicked(mx, my); }
    @Override public boolean mouseDragged(double mx, double my, int b, double dx, double dy) { return container.mouseDragged(my); }
    @Override public boolean mouseScrolled(double mx, double my, double a) { return container.mouseScrolled(a); }
    @Override public boolean mouseReleased(double mx, double my, int b) { container.mouseReleased(); return super.mouseReleased(mx, my, b); }

    private static class BossContainer {
        private final int x, y, w, h;
        private final int pad = 10;
        private double scroll = 0;
        private boolean dragging = false;
        private final List<BossRow> rows = new ArrayList<>();

        public int getY() {
            return this.y;
        }

        public BossContainer(ModNetworking.MenuDataPacket data) {
            this.w = (int) (MinecraftClient.getInstance().getWindow().getScaledWidth() * 0.3f);
            this.h = (int) (MinecraftClient.getInstance().getWindow().getScaledHeight() * 0.5f);
            this.x = (MinecraftClient.getInstance().getWindow().getScaledWidth() - w) / 2;
            this.y = (MinecraftClient.getInstance().getWindow().getScaledHeight() - h) / 2;
            for (BossConfig boss : data.bosses()) {
                rows.add(new BossRow(boss, data.playerSpawns().getOrDefault(boss.id, 0), data.limitPerBoss()));
            }
        }

        public void render(DrawContext ctx, int mx, int my) {
            drawBorder(ctx, x, y, w, h, 0xFF888888);
            ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF222222);

            int listAreaX = x + pad;
            int listAreaY = y + pad;
            int listAreaW = w - 40;
            int listAreaH = h - pad * 2;

            // 1. СНАЧАЛА ВКЛЮЧАЕМ SCISSOR
            ctx.enableScissor(listAreaX, listAreaY, listAreaX + listAreaW, listAreaY + listAreaH);

            int scrollOffset = (int)(scroll * Math.max(0, (rows.size() * 60) - listAreaH));

            // Поиск hovered (этот блок правильный)
            BossRow hovered = null;
            for (int i = 0; i < rows.size(); i++) {
                int rowY = listAreaY + (i * 60) - scrollOffset;
                if (mx >= listAreaX && mx <= x + w - 30 && my >= rowY && my <= rowY + 50) {
                    hovered = rows.get(i);
                }
            }

            // 2. ПОТОМ РЕНДЕРИМ СТРОКИ ВНУТРИ SCISSOR
            for (int i = 0; i < rows.size(); i++) {
                int rowY = listAreaY + (i * 60) - scrollOffset;
                rows.get(i).render(ctx, mx, my, listAreaX, rowY, listAreaW, 50, rows.get(i) == hovered);
            }

            // 3. ЗАТЕМ ОТКЛЮЧАЕМ
            ctx.disableScissor();

            // 4. Скроллбар и тултип рисуем ПОСЛЕ (они не должны обрезаться)
            int barX = x + w - 20;
            ctx.fill(barX, y + pad, barX + 6, y + h - pad, 0xFF000000);
            int thumbH = 40;
            int thumbY = y + pad + (int)(scroll * (h - pad * 2 - thumbH));
            ctx.fill(barX, thumbY, barX + 6, thumbY + thumbH, 0xFFAAAAAA);

            if (hovered != null) {
                ctx.drawTooltip(MinecraftClient.getInstance().textRenderer, hovered.getTooltip(), mx, my);
            }
        }

        public boolean mouseClicked(double mx, double my) {
            if (mx >= x + w - 25 && mx <= x + w - 10) { dragging = true; return true; }
            // Проверка клика по строкам (добавьте это)
            int listAreaX = x + pad;
            int listAreaY = y + pad;
            int scrollOffset = (int)(scroll * Math.max(0, (rows.size() * 60) - (h - pad * 2)));

            for (int i = 0; i < rows.size(); i++) {
                int rowY = listAreaY + (i * 60) - scrollOffset;
                if (mx >= listAreaX && mx <= x + w - 30 && my >= rowY && my <= rowY + 50) {
                    // Создаем буфер для отправки ID босса
                    PacketByteBuf buf = PacketByteBufs.create();
                    buf.writeString(rows.get(i).boss.id);

                    // Отправляем пакет серверу
                    ClientPlayNetworking.send(ModNetworking.SPAWN_BOSS_PACKET, buf);

                    // Закрываем экран после выбора (опционально)
                    MinecraftClient.getInstance().setScreen(null);
                    return true;
                }
            }
            return false;
        }
        public boolean mouseDragged(double my) {
            if (dragging) { scroll = Math.max(0, Math.min(1, (my - (y + pad)) / (h - pad * 2))); return true; }
            return false;
        }
        public void mouseReleased() { dragging = false; }
        public boolean mouseScrolled(double a) { scroll = Math.max(0, Math.min(1, scroll - a * 0.1)); return true; }
    }

    private static class BossRow {
        private final BossConfig boss;
        private final int s, l;
        private LivingEntity entity;

        public BossRow(BossConfig b, int s, int l) { this.boss = b; this.s = s; this.l = l;
            EntityType.get(b.id).ifPresent(type -> entity = (LivingEntity) type.create(MinecraftClient.getInstance().world));
        }

        public List<Text> getTooltip() {
            return List.of(Text.literal(boss.displayName), Text.literal(boss.description));
        }

        public void render(DrawContext ctx, int mx, int my, int x, int y, int w, int h, boolean isHovered) {
            int borderColor = isHovered ? 0xFFFFD700 : 0xFF555555; // Золотой или Серый
            drawBorder(ctx, x, y, w, h, borderColor);
            ctx.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF333333);

            int iconAreaSize = h - 10;
            int iconAreaX = x + 5;
            int iconAreaY = y + 5;
            drawBorder(ctx, iconAreaX, iconAreaY, iconAreaSize, iconAreaSize, 0xFF888888);

            // В методе render класса BossRow
            if (entity != null) {
                ctx.enableScissor(iconAreaX + 1, iconAreaY + 1, iconAreaX + iconAreaSize - 1, iconAreaY + iconAreaSize - 1);
                ctx.getVertexConsumers().draw();

                ctx.getMatrices().push();

                // 3. Центрируем и переносим модель
                ctx.getMatrices().translate(iconAreaX + iconAreaSize / 2f, iconAreaY + iconAreaSize - 5f, 50f);

                // 4. Масштабирование (ЗДЕСЬ МЕНЯЕМ РАЗМЕР: увеличь 0.7f, если нужно крупнее)
                float scale = (iconAreaSize * 0.7f) / Math.max(entity.getHeight(), 0.5f);
                ctx.getMatrices().scale(scale, -scale, scale);

                // 5. Поворот ВСЕЙ МОДЕЛИ (вместо поворота головы)
                float centerX = iconAreaX + iconAreaSize / 2f;
                float centerY = iconAreaY + iconAreaSize / 2f;
                float dx = mx - centerX;
                float dy = my - centerY;

                float yaw = (float) Math.toDegrees(Math.atan2(dx, 50.0));
                float pitch = (float) Math.toDegrees(Math.atan2(dy, 50.0)); // Инвертируем dy для корректного наклона

                // Применяем вращения к матрице (вращаем саму модель)
                ctx.getMatrices().multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees(yaw));
                ctx.getMatrices().multiply(net.minecraft.util.math.RotationAxis.POSITIVE_X.rotationDegrees(pitch));

                // Важно: сбрасываем стандартные углы сущности, чтобы они не конфликтовали с матрицей
                entity.setHeadYaw(0);
                entity.setBodyYaw(0);
                entity.setPitch(0);

                // 6. Рендерим сущность
                MinecraftClient.getInstance().getEntityRenderDispatcher().render(entity, 0, 0, 0, 0, 1.0f, ctx.getMatrices(), ctx.getVertexConsumers(), 0xF000F0);

                ctx.getMatrices().pop();
                ctx.getVertexConsumers().draw();
                ctx.disableScissor();
            }

            // Текст теперь с фиксированным отступом от правой границы иконки
            int textX = iconAreaX + iconAreaSize + 8;
            ctx.drawText(MinecraftClient.getInstance().textRenderer, boss.displayName, textX, y + 10, -1, true);
            ctx.drawText(MinecraftClient.getInstance().textRenderer, "Призывы: " + s + "/" + l, textX, y + 25, 0xAAAAAA, true);
        }
    }
}