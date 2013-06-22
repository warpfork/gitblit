package com.gitblit.transport.ssh;

import java.io.IOException;
import java.util.Scanner;

import org.apache.sshd.server.Command;
import org.apache.sshd.server.CommandFactory;
import org.apache.sshd.server.Environment;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PacketLineOut;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.transport.resolver.UploadPackFactory;
import com.gitblit.git.RepositoryResolver;

public class GitblitSshCommandFactory implements CommandFactory {
	public GitblitSshCommandFactory(RepositoryResolver<GitblitSshClient> repositoryResolver, UploadPackFactory<GitblitSshClient> uploadPackFactory, ReceivePackFactory<GitblitSshClient> receivePackFactory) {
		this.repositoryResolver = repositoryResolver;
		this.uploadPackFactory = uploadPackFactory;
		this.receivePackFactory = receivePackFactory;
	}

	private RepositoryResolver<GitblitSshClient> repositoryResolver;

	private UploadPackFactory<GitblitSshClient> uploadPackFactory;

	private ReceivePackFactory<GitblitSshClient> receivePackFactory;

	public Command createCommand(final String commandLine) {
		Scanner commandScanner = new Scanner(commandLine);
		final String command = commandScanner.next();
		final String argument = commandScanner.nextLine();

		if ("git-upload-pack".equals(command))
			return new UploadPackCommand(argument);
		if ("git-receive-pack".equals(command))
			return new ReceivePackCommand(argument);
		return new NonCommand();
	}
	
	public abstract class RepositoryCommand extends AbstractSshCommand {
		protected final String repositoryName;

		public RepositoryCommand(String repositoryName) {
			this.repositoryName = repositoryName;
		}

		public void start(Environment env) throws IOException {
			Repository db = null;
			try {
				GitblitSshClient client = session.getAttribute(GitblitSshClient.ATTR_KEY);
				db = selectRepository(client, repositoryName);
				if (db == null) return;
				run(client, db);
				exit.onExit(0);
			} catch (ServiceNotEnabledException e) {
				// Ignored. Client cannot use this repository.
			} catch (ServiceNotAuthorizedException e) {
				// Ignored. Client cannot use this repository.
			} finally {
				if (db != null)
					db.close();
				exit.onExit(1);
			}
		}

		protected Repository selectRepository(GitblitSshClient client, String name) throws IOException {
			try {
				return openRepository(client, name);
			} catch (ServiceMayNotContinueException e) {
				// An error when opening the repo means the client is expecting a ref
				// advertisement, so use that style of error.
				PacketLineOut pktOut = new PacketLineOut(out);
				pktOut.writeString("ERR " + e.getMessage() + "\n"); //$NON-NLS-1$ //$NON-NLS-2$
				return null;
			}
		}

		protected Repository openRepository(GitblitSshClient client, String name)
				throws ServiceMayNotContinueException {
			// Assume any attempt to use \ was by a Windows client
			// and correct to the more typical / used in Git URIs.
			//
			name = name.replace('\\', '/');

			// ssh://git@thishost/path should always be name="/path" here
			//
			if (!name.startsWith("/")) //$NON-NLS-1$
				return null;

			try {
				return repositoryResolver.open(client, name.substring(1));
			} catch (RepositoryNotFoundException e) {
				// null signals it "wasn't found", which is all that is suitable
				// for the remote client to know.
				return null;
			} catch (ServiceNotEnabledException e) {
				// null signals it "wasn't found", which is all that is suitable
				// for the remote client to know.
				return null;
			}
		}
		
		protected abstract void run(GitblitSshClient client, Repository db)
			throws IOException, ServiceNotEnabledException, ServiceNotAuthorizedException;
	}

	public class UploadPackCommand extends RepositoryCommand {
		public UploadPackCommand(String repositoryName) { super(repositoryName); }
		
		protected void run(GitblitSshClient client, Repository db)
				throws IOException, ServiceNotEnabledException, ServiceNotAuthorizedException {
			UploadPack up = uploadPackFactory.create(client, db);
			up.upload(in, out, null);
		}
	}

	public class ReceivePackCommand extends RepositoryCommand {
		public ReceivePackCommand(String repositoryName) { super(repositoryName); }
		
		protected void run(GitblitSshClient client, Repository db)
				throws IOException, ServiceNotEnabledException, ServiceNotAuthorizedException {
			ReceivePack rp = receivePackFactory.create(client, db);
			rp.receive(in, out, null);
		}
	}

	public static class NonCommand extends AbstractSshCommand {
		public void start(Environment env) {
			exit.onExit(127);
		}		
	}
}
