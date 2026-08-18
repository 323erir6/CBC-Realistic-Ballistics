package ua.ivan.cbcrealisticballistics;

import net.createmod.catnip.config.ui.BaseConfigScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

final class CBCRealisticBallisticsClient {
    private CBCRealisticBallisticsClient() {
    }

    static void registerConfigScreen(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (ignoredContainer, parent) ->
                        new BaseConfigScreen(parent, CBCRealisticBallistics.MOD_ID));
    }
}
