package com.gitblit.transport.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.sshd.server.Command;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SessionAware;
import org.apache.sshd.server.session.ServerSession;

abstract class AbstractSshCommand implements Command, SessionAware {

	protected InputStream in;

	protected OutputStream out;

	protected OutputStream err;

	protected ExitCallback exit;

	protected ServerSession session;

	public void setInputStream(InputStream in) {
		this.in = in;
	}

	public void setOutputStream(OutputStream out) {
		this.out = out;
	}

	public void setErrorStream(OutputStream err) {
		this.err = err;
	}

	public void setExitCallback(ExitCallback exit) {
		this.exit = exit;
	}

	public void setSession(final ServerSession session) {
		this.session = session;
	}

	public void destroy() {}

	public abstract void start(Environment env) throws IOException;
}
