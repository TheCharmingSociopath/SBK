/**
 * Copyright (c) KMG. All Rights Reserved..
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package io.sbk.gem;

import org.apache.commons.lang.StringUtils;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.scp.client.ScpClient;
import org.apache.sshd.scp.client.ScpClientCreator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Ssh {


    private static ClientSession createSession(SshClient client, SshConnection conn, long timeoutSeconds)
            throws IOException {
        // Connect to the server
        final ConnectFuture cf = client.connect(conn.getUserName(), conn.getHost(), conn.getPort());
        final ClientSession session = cf.verify().getSession();

        if (StringUtils.isNotEmpty(conn.getPassword())) {
            session.addPasswordIdentity(conn.getPassword());
            session.auth().verify(TimeUnit.SECONDS.toMillis(timeoutSeconds));
        }
        return session;
    }


    private static SshResponse runCommand(SshClient client, SshConnection conn, long timeoutSeconds, String cmd)
            throws TimeoutException, IOException {

        final ClientSession session = createSession(client, conn, timeoutSeconds);

        // Create the exec and channel its output/error streams
        final ChannelExec execChannel = session.createExecChannel(cmd);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        execChannel.setOut(out);
        execChannel.setErr(err);

        // Execute and wait
        execChannel.open();
        final Set<?> events = execChannel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED),
                TimeUnit.SECONDS.toMillis(timeoutSeconds));
        execChannel.close(false);
        session.close(false);

        // Check if timed out
        if (events.contains(ClientChannelEvent.TIMEOUT)) {
            throw new TimeoutException();
        }

        // Respond
        return new SshResponse(out.toString(), err.toString(), execChannel.getExitStatus());

    }


    /**
     * Runs a SSH command against a remote system wait till it completes or timeout.
     *
     * @param conn  ssh connection
     * @param cmd The command to run.
     * @param timeoutSeconds The amount of time to wait for the command to run before timing out. This is in
     *        seconds. This is used as two separate timeouts, one for login another for command
     *        execution.
     * @return The SshResponse contains the output of a successful command.
     * @throws TimeoutException Raised if the command times out.
     * @throws IOException Raised in the event of a general failure (wrong authentication or something
     *         of that nature).
     */
    public static SshResponse runCommand(SshConnection conn, long timeoutSeconds, String cmd)
            throws TimeoutException, IOException {
        final SshClient client = SshClient.setUpDefaultClient();
        client.start();

        try {
            final SshResponse ret = runCommand(client, conn, timeoutSeconds, cmd);
            client.stop();
            return  ret;
        } catch (Exception ex) {
            client.stop();
            throw  ex;
        }
    }

    /**
     * Runs a SSH command against a remote system asynchronously.
     *
     * @param conn ssh connection
     * @param cmd The command to run.
     * @param timeoutSeconds The amount of time to wait for the command to run before timing out. This is in
     *        seconds. This is used as two separate timeouts, one for login another for command
     *        execution.
     * @param executor Executor service
     * @return Completable Future with SshResponse.
     */
    public static CompletableFuture<SshResponse> runCommandAsync(SshConnection conn, long timeoutSeconds, String cmd,
                                                                 ExecutorService executor)  {
        final CompletableFuture<SshResponse> retFuture = new CompletableFuture<>();
        final SshClient client = SshClient.setUpDefaultClient();
        client.start();

        if (executor != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    retFuture.complete(runCommand(client, conn, timeoutSeconds, cmd));
                } catch (Throwable ex) {
                    retFuture.completeExceptionally(ex);
                }
            }, executor);
        } else {
            CompletableFuture.runAsync(() -> {
                try {
                    retFuture.complete(runCommand(client, conn, timeoutSeconds, cmd));
                } catch (Throwable ex) {
                    retFuture.completeExceptionally(ex);
                }
            });
        }

        retFuture.exceptionally(ex -> {
            client.stop();
            return null;
        });

        retFuture.thenAccept( ret -> {
            client.stop();
        });

        return retFuture;
    }


    private static void copyDirectory(SshClient client, SshConnection conn, long timeoutSeconds, String srcPath,
                                      String dstPath)  throws IOException {
        final ClientSession session = createSession(client, conn, timeoutSeconds);

        final ScpClientCreator creator = ScpClientCreator.instance();
        final ScpClient scpClient = creator.createScpClient(session);

        scpClient.upload(srcPath, dstPath, ScpClient.Option.Recursive, ScpClient.Option.PreserveAttributes,
                ScpClient.Option.TargetIsDirectory);

        session.close(false);
    }

    public static void copyDirectory(SshConnection conn, long timeoutSeconds, String srcPath, String dstPath)
            throws IOException {
        final SshClient client = SshClient.setUpDefaultClient();
        client.start();
        try {
            copyDirectory(client, conn, timeoutSeconds, srcPath, dstPath);
            client.stop();
        } catch (Exception ex) {
            client.stop();
            throw  ex;
        }
    }


    public static CompletableFuture<Void> copyDirectoryAsync(SshConnection conn, long timeoutSeconds, String srcPath,
                                                             String dstPath, ExecutorService executor) {
        final CompletableFuture<Void> retFuture = new CompletableFuture<>();
        final SshClient client = SshClient.setUpDefaultClient();
        client.start();

        if (executor != null) {
            CompletableFuture.runAsync(() -> {
                try {
                    copyDirectory(client, conn, timeoutSeconds, srcPath, dstPath);
                    retFuture.complete(null);
                } catch (Throwable ex) {
                    retFuture.completeExceptionally(ex);
                }
            }, executor);
        } else {
            CompletableFuture.runAsync(() -> {
                try {
                    copyDirectory(client, conn, timeoutSeconds, srcPath, dstPath);
                    retFuture.complete(null);
                } catch (Throwable ex) {
                    retFuture.completeExceptionally(ex);
                }
            });
        }

        retFuture.exceptionally(ex -> {
            client.stop();
            return null;
        });

        retFuture.thenAccept( ret -> {
            client.stop();
        });

        return retFuture;
    }


}