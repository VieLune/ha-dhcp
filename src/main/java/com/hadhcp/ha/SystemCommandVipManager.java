package com.hadhcp.ha;

import com.hadhcp.config.HaProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SystemCommandVipManager implements VipManager {

    private static final Logger log = LoggerFactory.getLogger(SystemCommandVipManager.class);

    private final HaProperties haProperties;
    private final VipInspector vipInspector;

    public SystemCommandVipManager(HaProperties haProperties, VipInspector vipInspector) {
        this.haProperties = haProperties;
        this.vipInspector = vipInspector;
    }

    @Override
    public boolean ensureVip(String vip, String interfaceName, int prefixLength) {
        if (!StringUtils.hasText(interfaceName)) {
            log.warn("Cannot bind VIP {} without an interface name", vip);
            return false;
        }
        if (vipInspector.hasAddress(vip, interfaceName)) {
            return true;
        }

        if (!run(List.of("ip", "address", "add", vip + "/" + prefixLength, "dev", interfaceName))) {
            return false;
        }
        run(List.of("arping", "-U", "-c", "3", "-I", interfaceName, vip));
        return vipInspector.hasAddress(vip, interfaceName);
    }

    @Override
    public boolean releaseVip(String vip, String interfaceName) {
        if (!StringUtils.hasText(interfaceName)) {
            log.warn("Cannot release VIP {} without an interface name", vip);
            return false;
        }
        if (!vipInspector.hasAddress(vip, interfaceName)) {
            return true;
        }

        if (!run(List.of("ip", "address", "del", vip + "/" + haProperties.getPrefixLength(), "dev", interfaceName))) {
            return false;
        }
        return !vipInspector.hasAddress(vip, interfaceName);
    }

    private boolean run(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeout().toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("VIP command timed out: {}", command);
                return false;
            }
            if (process.exitValue() != 0) {
                log.warn("VIP command failed with exitCode={}: {}", process.exitValue(), command);
                return false;
            }
            return true;
        } catch (IOException ex) {
            log.warn("Failed to execute VIP command: {}", command, ex);
            return false;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while executing VIP command: {}", command, ex);
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private Duration timeout() {
        return Duration.ofSeconds(Math.max(1, haProperties.getCommandTimeoutSeconds()));
    }
}
