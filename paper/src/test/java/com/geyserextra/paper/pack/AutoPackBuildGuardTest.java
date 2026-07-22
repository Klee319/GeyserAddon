package com.geyserextra.paper.pack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutoPackBuildGuardTest {

    @Test
    void allowsPackGenerationInsideTheSynchronousStartupWindow() {
        AutoPackBuildGuard guard = new AutoPackBuildGuard();

        guard.runStartupScan(false, () ->
            assertThat(guard.mayReplacePack()).isTrue());
    }

    @Test
    void blocksPackReplacementAfterTheStartupWindowCloses() {
        AutoPackBuildGuard guard = new AutoPackBuildGuard();

        guard.runStartupScan(false, () -> {});

        assertThat(guard.mayReplacePack()).isFalse();
    }

    @Test
    void closesTheStartupWindowWhenTheImmediateScanFails() {
        AutoPackBuildGuard guard = new AutoPackBuildGuard();

        try {
            guard.runStartupScan(false, () -> {
                throw new IllegalStateException("scan failed");
            });
        } catch (IllegalStateException ignored) {
            // The caller retains the original startup failure.
        }

        assertThat(guard.mayReplacePack()).isFalse();
    }

    @Test
    void keepsTheWindowClosedWhenGeyserIsAlreadyRunningDuringReload() {
        AutoPackBuildGuard guard = new AutoPackBuildGuard();

        guard.runStartupScan(true, () ->
            assertThat(guard.mayReplacePack()).isFalse());

        assertThat(guard.mayReplacePack()).isFalse();
    }
}
