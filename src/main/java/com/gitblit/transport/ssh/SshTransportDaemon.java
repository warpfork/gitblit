/*
 * Copyright 2013 gitblit.com.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.gitblit.transport.ssh;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.keyprovider.PEMGeneratorHostKeyProvider;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.gitblit.git.GitblitReceivePackFactory;
import com.gitblit.git.GitblitUploadPackFactory;
import com.gitblit.git.RepositoryResolver;
import com.gitblit.utils.StringUtils;

/**
 * Manager for the ssh transport. Roughly analogous to the
 * {@link com.gitblit.git.GitDaemon} class.
 * 
 * @author Eric Myhre
 * 
 */
public class SshTransportDaemon {

	private final Logger logger = LoggerFactory.getLogger(SshTransportDaemon.class);

	/**
	 * 22: IANA assigned port number for ssh. Note that this is a distinct concept
	 * from gitblit's default conf for ssh port -- this "default" is what the git
	 * protocol itself defaults to if it sees and ssh url without a port.
	 */
	public static final int DEFAULT_PORT = 22;
	
	private static final String HOST_KEY_STORE = "sshKeyStore.pem";

	private InetSocketAddress myAddress;

	private AtomicBoolean run;

	private GitblitSshServer sshd;

	private RepositoryResolver<GitblitSshClient> repositoryResolver;

	private UploadPackFactory<GitblitSshClient> uploadPackFactory;

	private ReceivePackFactory<GitblitSshClient> receivePackFactory;

	/**
	 * Construct the Gitblit ssh daemon.
	 * 
	 * @param bindInterface
	 *            the ip address of the interface to bind
	 * @param port
	 *            the port to serve on
	 * @param baseFolder
	 *            the folder for gitblit configuration (host keys will either be
	 *            loaded from here or created here if necessary).
	 * @param repositoryFolder
	 *            the folder to serve from
	 */
	public SshTransportDaemon(String bindInterface, int port, File baseFolder, File repositoryFolder) {
		this(
			StringUtils.isEmpty(bindInterface) ? new InetSocketAddress(port) : new InetSocketAddress(bindInterface, port),
			baseFolder,
			repositoryFolder
		);
	}
	
	/**
	 * Configure a new daemon for the specified network address.
	 * 
	 * @param addr
	 *            address to listen for connections on. If null, any available
	 *            port will be chosen on all network interfaces.
	 * @param baseFolder
	 *            the folder for gitblit configuration (host keys will either be
	 *            loaded from here or created here if necessary).
	 */
	public SshTransportDaemon(final InetSocketAddress addr, File baseFolder, File repositoryFolder) {
		myAddress = addr;

		sshd = new GitblitSshServer();
		sshd.setPort(addr.getPort());
		sshd.setHost(addr.getHostName());
		sshd.setup();
		sshd.setKeyPairProvider(new PEMGeneratorHostKeyProvider(new File(baseFolder, HOST_KEY_STORE).getPath()));
		sshd.setPublickeyAuthenticator(new UserServicePublickeyAuthenticator());

		run = new AtomicBoolean(false);
		repositoryResolver = new RepositoryResolver<GitblitSshClient>(repositoryFolder);
		uploadPackFactory = new GitblitUploadPackFactory<GitblitSshClient>();
		receivePackFactory = new GitblitReceivePackFactory<GitblitSshClient>();

		sshd.setCommandFactory(new GitblitSshCommandFactory(
				repositoryResolver,
				uploadPackFactory,
				receivePackFactory
		));
	}
	
	public int getPort() {
		return myAddress.getPort();
	}
	
	public String formatUrl(String gituser, String servername, String repository) {
		if (getPort() == DEFAULT_PORT) {
			// standard port
			return MessageFormat.format("{0}@{1}/{2}", gituser, servername, repository);
		} else {
			// non-standard port
			return MessageFormat.format("ssh://{0}@{1}:{2,number,0}/{3}", gituser, servername, getPort(), repository);
		}
	}

	/**
	 * Start this daemon on a background thread.
	 * 
	 * @throws IOException
	 *             the server socket could not be opened.
	 * @throws IllegalStateException
	 *             the daemon is already running.
	 */
	public synchronized void start() throws IOException {
		if (run.get())
			throw new IllegalStateException(JGitText.get().daemonAlreadyRunning);

		sshd.start();
		run.set(true);
		
		logger.info(MessageFormat.format("Ssh Daemon is listening on {0}:{1,number,0}", myAddress.getAddress().getHostAddress(), myAddress.getPort()));
	}

	/** @return true if this daemon is receiving connections. */
	public boolean isRunning() {
		return run.get();
	}

	/** Stop this daemon. */
	public synchronized void stop() {
		if (run.get()) {
			logger.info("Ssh Daemon stopping...");
			run.set(false);
			
			try {
				sshd.stop();
			} catch (InterruptedException e) {
				logger.error("Ssh Daemon stop interrupted", e);
			}
		}
	}
}
