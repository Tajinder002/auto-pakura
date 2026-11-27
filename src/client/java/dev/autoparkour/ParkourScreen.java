package dev.autoparkour;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.List;

/** Simple tabbed GUI: Checkpoints / Status / Runs / Settings. Open with K, close with Esc or Close. */
public final class ParkourScreen extends Screen {
    private enum Tab {
        CHECKPOINTS("Checkpoints"), STATUS("Status"), RUNS("Runs"), SETTINGS("Settings");
        final String label;
        Tab(String l) { label = l; }
    }

    private static Tab tab = Tab.CHECKPOINTS;
    private static final int PER = 5;
    private static final Double[] MARGINS = {null, 0.0, 0.07, 0.12, 0.2};
    private int page;
    private String note = "";
    private List<String> logCache = List.of();
    private int logAge = 999;

    public ParkourScreen() { super(Text.literal("Auto Parkour")); }

    @Override public boolean shouldPause() { return false; }

    private MinecraftClient mc() { return MinecraftClient.getInstance(); }

    private ButtonWidget btn(String label, int x, int y, int w, Runnable r) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(label), bb -> r.run()).dimensions(x, y, w, 20).build();
        addDrawableChild(b);
        return b;
    }

    @Override
    protected void init() {
        int cx = width / 2, x0 = cx - 170;
        Tab[] ts = Tab.values();
        for (int i = 0; i < ts.length; i++) {
            Tab t = ts[i];
            ButtonWidget b = btn(t.label, x0 + i * 86, 20, 82, () -> { tab = t; page = 0; note = ""; clearAndInit(); });
            b.active = t != tab;
        }
        switch (tab) {
            case CHECKPOINTS -> buildCheckpoints(x0);
            case STATUS -> btn("STOP", x0, height - 52, 100, () -> { AutoGo.I.halt(mc()); });
            case RUNS -> buildRuns(x0);
            case SETTINGS -> buildSettings(x0);
        }
        btn("Close", cx - 40, height - 26, 80, this::close);
    }

    // ---------------- checkpoints ----------------
    private void buildCheckpoints(int x0) {
        List<BlockPos> cps = AutoGo.I.checkpoints();
        int pages = Math.max(1, (cps.size() + PER - 1) / PER);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < PER; i++) {
            int idx = page * PER + i;
            if (idx >= cps.size()) break;
            int y = 48 + i * 22;
            btn("^", x0 + 262, y, 24, () -> { if (idx > 0) Collections.swap(cps, idx, idx - 1); clearAndInit(); });
            btn("v", x0 + 288, y, 24, () -> { if (idx < cps.size() - 1) Collections.swap(cps, idx, idx + 1); clearAndInit(); });
            btn("x", x0 + 314, y, 24, () -> { cps.remove(idx); clearAndInit(); });
        }
        int y = 48 + PER * 22 + 6;
        btn("Add block I look at", x0, y, 168, () -> {
            if (mc().crosshairTarget instanceof BlockHitResult h && h.getType() == HitResult.Type.BLOCK) {
                cps.add(h.getBlockPos().toImmutable()); note = "Added"; clearAndInit();
            } else note = "Not looking at a block";
        });
        btn("Add block under me", x0 + 172, y, 168, () -> {
            if (mc().player != null) {
                cps.add(BlockPos.ofFloored(mc().player.getX(), mc().player.getY() - 0.5, mc().player.getZ()));
                note = "Added"; clearAndInit();
            }
        });
        btn("< Prev", x0, y + 24, 80, () -> { page = Math.max(0, page - 1); clearAndInit(); });
        btn("Next >", x0 + 84, y + 24, 80, () -> { page = Math.min(pages - 1, page + 1); clearAndInit(); });
        btn("Clear all", x0 + 258, y + 24, 82, () -> { cps.clear(); page = 0; clearAndInit(); });
        btn("GO", x0, y + 50, 168, () -> { mc().setScreen(null); AutoGo.I.toggleGo(mc()); });
        btn("STOP", x0 + 172, y + 50, 168, () -> AutoGo.I.halt(mc()));
    }

    // ---------------- runs ----------------
    private void buildRuns(int x0) {
        String map = ParkourClient.mapId(mc());
        ParkourClient pc = ParkourClient.I;
        btn(pc.recordingNow() ? "Stop + save run" : "Record new run", x0, 48, 168, () -> { pc.toggleRecord(mc()); mc().setScreen(null); });
        btn(pc.playingNow() ? "Stop playing" : "Play fastest run", x0 + 172, 48, 168, () -> { pc.togglePlay(mc()); mc().setScreen(null); });
        List<Run.Info> runs = Run.list(map);
        int pages = Math.max(1, (runs.size() + PER - 1) / PER);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < PER; i++) {
            int idx = page * PER + i;
            if (idx >= runs.size()) break;
            String name = runs.get(idx).name();
            btn("Delete", x0 + 270, 78 + i * 22, 70, () -> { Run.delete(map, name); clearAndInit(); });
        }
        int y = 78 + PER * 22 + 6;
        btn("< Prev", x0, y, 80, () -> { page = Math.max(0, page - 1); clearAndInit(); });
        btn("Next >", x0 + 84, y, 80, () -> { page = Math.min(pages - 1, page + 1); clearAndInit(); });
        btn("Delete all runs", x0 + 220, y, 120, () -> { Run.clear(map); note = "Deleted"; clearAndInit(); });
    }

    // ---------------- settings ----------------
    private String marginLabel() {
        Double o = Learn.override();
        return o == null ? String.format("Auto (%.2f now)", Learn.margin()) : String.format("%.2f", o);
    }

    private void buildSettings(int x0) {
        btn("Debug view: " + (AutoGo.I.debug() ? "ON" : "OFF"), x0, 52, 250, () -> { AutoGo.I.setDebug(!AutoGo.I.debug()); clearAndInit(); });
        btn("Safety margin: " + marginLabel(), x0, 76, 250, () -> {
            int cur = 0;
            for (int i = 0; i < MARGINS.length; i++) if ((MARGINS[i] == null && Learn.override() == null) || (MARGINS[i] != null && MARGINS[i].equals(Learn.override()))) cur = i;
            Learn.setOverride(MARGINS[(cur + 1) % MARGINS.length]);
            clearAndInit();
        });
        btn("Reset learning for this map", x0, 100, 250, () -> {
            String map = ParkourClient.mapId(mc());
            Learn.ensure(map); Learn.reset(map); note = "Learning reset"; clearAndInit();
        });
    }

    // ---------------- drawing ----------------
    private void text(DrawContext c, String s, int x, int y, int color) { c.drawTextWithShadow(textRenderer, s, x, y, color); }

    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        super.render(c, mx, my, delta);
        int x0 = width / 2 - 170;
        text(c, "Auto Parkour", x0, 6, 0xFFFFFFFF);
        AutoGo g = AutoGo.I;
        switch (tab) {
            case CHECKPOINTS -> {
                List<BlockPos> cps = g.checkpoints();
                if (cps.isEmpty()) text(c, "No checkpoints. Add one below, then press GO.", x0, 54, 0xFFAAAAAA);
                for (int i = 0; i < PER; i++) {
                    int idx = page * PER + i;
                    if (idx >= cps.size()) break;
                    BlockPos b = cps.get(idx);
                    text(c, (idx + 1) + ".  " + b.getX() + "  " + b.getY() + "  " + b.getZ(), x0, 54 + i * 22, 0xFFFFFFFF);
                }
                int y = 48 + PER * 22 + 6;
                text(c, "Page " + (page + 1) + "   " + note, x0 + 170, y + 30, 0xFFAAAAAA);
            }
            case STATUS -> {
                text(c, "State: " + g.statusLine(), x0, 52, 0xFFFFFFFF);
                text(c, "Move " + g.moveNo() + " of " + g.moveTotal() + "     re-plans: " + g.replans(), x0, 66, 0xFFFFFFFF);
                text(c, "Checkpoints left: " + g.checkpoints().size() + (g.tight() ? "     (tight jumps, less safe)" : ""), x0, 80, 0xFFFFFFFF);
                text(c, String.format("Safety margin: %.2f     avg landing error: %.3f", Learn.margin(), Learn.avgErr()), x0, 94, 0xFFFFFFFF);
                text(c, "Moves remembered: " + Learn.movesTracked() + "     banned: " + Learn.bannedCount(), x0, 108, 0xFFFFFFFF);
                text(c, "Last moves (OK / MISS):", x0, 126, 0xFFAAAAAA);
                if (++logAge > 20) { logCache = Log.tail(ParkourClient.mapId(mc()), 6); logAge = 0; }
                // log lines are long; show the key facts only
                List<String> lines = logCache;
                for (int i = 0; i < lines.size(); i++) {
                    String s = lines.get(i);
                    text(c, s.length() > 58 ? s.substring(0, 58) : s, x0, 138 + i * 10, s.contains("MISS") ? 0xFFFF7777 : 0xFF88FF88);
                }
            }
            case RUNS -> {
                text(c, "Mode: " + (ParkourClient.I.recordingNow() ? "RECORDING" : ParkourClient.I.playingNow() ? "PLAYING" : "idle"), x0, 36 + 6, 0xFFAAAAAA);
                List<Run.Info> runs = Run.list(ParkourClient.mapId(mc()));
                if (runs.isEmpty()) text(c, "No saved runs for this map.", x0, 84, 0xFFAAAAAA);
                for (int i = 0; i < PER; i++) {
                    int idx = page * PER + i;
                    if (idx >= runs.size()) break;
                    Run.Info r = runs.get(idx);
                    text(c, (idx == 0 ? "[fastest] " : "") + r.ticks() + " ticks", x0, 84 + i * 22, 0xFFFFFFFF);
                }
                text(c, note, x0 + 170, 78 + PER * 22 + 12, 0xFFAAAAAA);
            }
            case SETTINGS -> {
                text(c, "Safety margin: how far off its start spot a jump must", x0, 130, 0xFFAAAAAA);
                text(c, "still work. 0.00 = tight jumps allowed (less safe).", x0, 142, 0xFFAAAAAA);
                text(c, "Logs: config/autoparkour/_logs/<map>.txt", x0, 160, 0xFFAAAAAA);
                text(c, note, x0, 180, 0xFFAAAAAA);
            }
        }
    }
}
