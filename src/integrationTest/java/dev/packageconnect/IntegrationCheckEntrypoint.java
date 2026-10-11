package dev.packageconnect;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * 仅由开发服务器加载，写入当次自检结果并关闭测试世界。 Loaded only by the development server; writes this run's check result
 * and shuts down the test world.
 */
@EventBusSubscriber(modid = PackageConnect.ID)
public final class IntegrationCheckEntrypoint {
  private IntegrationCheckEntrypoint() {}

  /** 在服务器启动完成后调度会修改世界的自检。 Schedules world-mutating checks after the server has started. */
  @SubscribeEvent
  public static void onServerStarted(ServerStartedEvent event) {
    var server = event.getServer();
    server.execute(
        () -> {
          String result = "FAIL\n";
          try {
            int assertions = IntegrationChecks.run(server);
            result = "PASS " + assertions + "\n";
            LogUtils.getLogger()
                .info(
                    "PACKAGECONNECT SELFTEST PASS: {} assertions; {}",
                    assertions,
                    StorageCache.stats());
          } catch (Throwable error) {
            LogUtils.getLogger().error("PACKAGECONNECT SELFTEST FAIL", error);
          } finally {
            try {
              Path report = Path.of(System.getProperty("packageconnect.selftest.report"));
              Files.createDirectories(report.getParent());
              Files.writeString(report, result);
            } catch (IOException | RuntimeException error) {
              LogUtils.getLogger().error("Cannot write PackageConnect self-test result", error);
            } finally {
              server.halt(false);
            }
          }
        });
  }
}
