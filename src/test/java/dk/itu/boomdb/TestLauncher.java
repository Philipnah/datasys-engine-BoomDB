package dk.itu.boomdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds a subprocess command for the packaged launcher on supported platforms. */
final class TestLauncher {
    private TestLauncher() { }

    static ProcessBuilder command(String... args) {
        List<String> command = new ArrayList<>();
        Path script = Path.of("engine.sh").toAbsolutePath();
        if (System.getProperty("os.name").startsWith("Windows")) {
            Path bash = Path.of("C:/Program Files/Git/bin/bash.exe");
            if (Files.exists(bash)) {
                command.add(bash.toString());
                command.add(script.toString().replace('\\', '/'));
            } else {
                command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
                command.add("-jar");
                command.add(Path.of("target/BoomDB.jar").toAbsolutePath().toString());
            }
        } else {
            command.add(script.toString());
        }
        command.addAll(List.of(args));
        return new ProcessBuilder(command);
    }
}
