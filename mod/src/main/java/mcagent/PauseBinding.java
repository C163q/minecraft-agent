package mcagent;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

public final class PauseBinding {
    public static final KeyMapping KEY =
        new KeyMapping("key.minecraft_agent.pause", InputConstants.KEY_F8, KeyMapping.Category.MISC);

    private PauseBinding() {}
}
