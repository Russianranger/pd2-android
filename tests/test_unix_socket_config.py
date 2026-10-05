#!/usr/bin/env python3
"""Compile production socket config and exercise real Linux Unix-domain binds on JDK 17."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = r"""
import com.winlator.xconnector.UnixSocketConfig;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public final class UnixSocketBindCheck {
    private static final String[] ENDPOINTS = {
        UnixSocketConfig.SYSVSHM_SERVER_PATH, UnixSocketConfig.XSERVER_PATH,
        UnixSocketConfig.ALSA_SERVER_PATH, UnixSocketConfig.PULSE_SERVER_PATH,
        UnixSocketConfig.VIRGL_SERVER_PATH, UnixSocketConfig.VORTEK_SERVER_PATH
    };
    private static int missingParentFailures, binds;

    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(args[0]);
        for (int i = 0; i < ENDPOINTS.length; i++) {
            Path root = Files.createDirectory(directory.resolve("r" + i));
            UnixSocketConfig config = UnixSocketConfig.create(root.toString(), ENDPOINTS[i]);
            Path endpoint = Paths.get(config.path);
            Files.createDirectories(endpoint.getParent());
            Files.write(endpoint.getParent().resolve("previous-session-marker"), new byte[]{1});
            // Exact ordering of the reported startup failure: construct configs, then clear tmp.
            clear(root.resolve("tmp"));
            if (Files.exists(endpoint.getParent())) throw new AssertionError("tmp clear did not remove the old parent");
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                try {
                    server.bind(UnixDomainSocketAddress.of(config.path));
                    throw new AssertionError("Missing-parent regression did not reproduce for " + config.relativePath);
                } catch (IOException expected) { missingParentFailures++; }
            }
            // Production preparation must be immediately before bind, after the clear.
            config.prepareForBind();
            bindAndClose(config);
            if (!Files.exists(endpoint)) throw new AssertionError("Expected a real stale Unix socket inode");
            // Rebinding without clearing removes this endpoint only, not the directory.
            config.prepareForBind();
            bindAndClose(config);
            // A same-app second session also works after the old session's tmp cleanup.
            clear(root.resolve("tmp"));
            config.prepareForBind();
            bindAndClose(config);
        }
        liveNeighborIsPreserved(directory);
        if (missingParentFailures != 6 || binds != 19) throw new AssertionError("Coverage counts incomplete");
        System.out.println("PASS: 6 missing-parent Unix bind failures reproduced; 19 prepared real binds passed; live socket neighbor preserved");
    }

    private static void liveNeighborIsPreserved(Path directory) throws Exception {
        Path root = Files.createDirectory(directory.resolve("neighbors"));
        UnixSocketConfig pulse = UnixSocketConfig.create(root.toString(), UnixSocketConfig.PULSE_SERVER_PATH);
        pulse.prepareForBind();
        try (ServerSocketChannel neighbor = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            neighbor.bind(UnixDomainSocketAddress.of(pulse.path));
            Path marker = Paths.get(pulse.path).getParent().resolve("retain.txt");
            Files.write(marker, new byte[]{4, 2});
            UnixSocketConfig alsa = UnixSocketConfig.create(root.toString(), UnixSocketConfig.ALSA_SERVER_PATH);
            if (!Files.exists(Paths.get(pulse.path)) || !Files.exists(marker))
                throw new AssertionError("Config construction destroyed a socket neighbor or parent contents");
            alsa.prepareForBind();
            bindAndClose(alsa);
            if (!Files.exists(Paths.get(pulse.path)) || Files.readAllBytes(marker)[0] != 4)
                throw new AssertionError("Preparation destroyed a sibling endpoint or parent contents");
            try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.connect(UnixDomainSocketAddress.of(pulse.path));
                try (SocketChannel accepted = neighbor.accept()) {
                    client.write(ByteBuffer.wrap(new byte[]{7}));
                    ByteBuffer received = ByteBuffer.allocate(1);
                    if (accepted.read(received) != 1 || received.array()[0] != 7)
                        throw new AssertionError("Sibling socket is no longer reachable");
                }
            }
        }
    }

    private static void bindAndClose(UnixSocketConfig config) throws Exception {
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(config.path));
            binds++;
        }
    }

    private static void clear(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                if (!path.equals(directory)) Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
"""


def java_tool(name):
    java_home = os.environ.get("JAVA_HOME")
    return str(Path(java_home) / "bin" / name) if java_home else name


def main():
    # Short paths also stay within Linux sockaddr_un's 108-byte endpoint limit.
    with tempfile.TemporaryDirectory(prefix="pd2-unix-") as temp:
        directory = Path(temp)
        fixture = directory / "UnixSocketBindCheck.java"
        fixture.write_text(FIXTURE)
        production = ROOT / "app/src/main/java/com/winlator/xconnector/UnixSocketConfig.java"
        subprocess.run([java_tool("javac"), "--release", "17", "-d", str(directory),
                        str(production), str(fixture)], check=True, timeout=60)
        subprocess.run([java_tool("java"), "-cp", str(directory), "UnixSocketBindCheck", str(directory)],
                       check=True, timeout=30)


if __name__ == "__main__":
    main()
