/**
 *  BlueCove - Java library for Bluetooth
 *  Copyright (C) 2007-2008 Vlad Skarzhevskyy
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *
 *  @version $Id$
 */
package com.intel.bluetooth.obex;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import javax.obex.HeaderSet;
import javax.obex.Operation;

import com.intel.bluetooth.DebugLog;

abstract class OBEXClientOperation implements Operation, OBEXOperation {

	protected OBEXClientSessionImpl session;

	protected char operationId;

	protected HeaderSet replyHeaders;

	protected boolean isClosed;

	protected boolean operationInProgress;

	protected OBEXOperationOutputStream outputStream;

	protected boolean outputStreamOpened = false;

	protected OBEXOperationInputStream inputStream;

	protected boolean inputStreamOpened = false;

	protected boolean errorReceived = false;

	protected boolean requestEnded = false;

	protected boolean finalBodyReceived = false;

	protected Object lock;

	OBEXClientOperation(OBEXClientSessionImpl session, char operationId) throws IOException {
		this.session = session;
		this.operationId = operationId;
		this.isClosed = false;
		this.operationInProgress = false;
		this.lock = new Object();
	}

	protected void startOperation(HeaderSet sendHeaders) throws IOException {
		this.operationInProgress = true;
		exchangePacket(OBEXHeaderSetImpl.toByteArray(sendHeaders));
	}

	protected void endRequestPhase() throws IOException {
		if (requestEnded) {
			return;
		}
		this.operationInProgress = false;
		this.requestEnded = true;
		this.operationId |= OBEXOperationCodes.FINAL_BIT;
		exchangePacket(null);
	}

	protected void exchangePacket(byte[] data) throws IOException {
		boolean success = false;
		try {
			session.writeOperation(this.operationId, data);
			byte[] b = session.readOperation();
			HeaderSet dataHeaders = OBEXHeaderSetImpl.readHeaders(b[0], b, 3);
			int responseCode = dataHeaders.getResponseCode();
			DebugLog.debug0x("client operation got reply", OBEXUtils.toStringObexResponseCodes(responseCode),
					responseCode);
			switch (responseCode) {
			case OBEXOperationCodes.OBEX_RESPONSE_SUCCESS:
				processIncommingHeaders(dataHeaders);
				processIncommingData(dataHeaders, true);
				this.operationInProgress = false;
				break;
			case OBEXOperationCodes.OBEX_RESPONSE_CONTINUE:
				processIncommingHeaders(dataHeaders);
				processIncommingData(dataHeaders, false);
				break;
			default:
				errorReceived = true;
				// responseCode may be reported by getResponseCode()
				processIncommingHeaders(dataHeaders);
				if ((this.operationId & OBEXOperationCodes.FINAL_BIT) == 0) {
					throw new IOException("Operation error, 0x" + Integer.toHexString(responseCode) + " "
							+ OBEXUtils.toStringObexResponseCodes(responseCode));
				}
			}
			success = true;
		} finally {
			if (!success) {
				errorReceived = true;
			}
		}
	}

	protected void processIncommingHeaders(HeaderSet dataHeaders) throws IOException {
		if (replyHeaders != null) {
			OBEXHeaderSetImpl.appendHeaders(dataHeaders, replyHeaders);
		}
		// replyHeaders will contain responseCode from last reply
		this.replyHeaders = dataHeaders;
	}

	protected void processIncommingData(HeaderSet dataHeaders, boolean eof) throws IOException {
		byte[] data = (byte[]) dataHeaders.getHeader(OBEXHeaderSetImpl.OBEX_HDR_BODY);
		if (data == null) {
			data = (byte[]) dataHeaders.getHeader(OBEXHeaderSetImpl.OBEX_HDR_BODY_END);
			if (data != null) {
				finalBodyReceived = true;
				eof = true;
			}
		}
		if (data != null) {
			DebugLog.debug("client received Data eof " + eof + " len", data.length);
			inputStream.appendData(data, eof);
		} else if (eof) {
			inputStream.appendData(null, eof);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.obex.Operation#abort()
	 */
	public void abort() throws IOException {
		validateOperationIsOpen();
		if (!this.operationInProgress) {
			throw new IOException("the transaction has already ended");
		}
		synchronized (lock) {
			if (outputStream != null) {
				outputStream.abort();
			}
			this.inputStream.close();
		}
		writeAbort();
	}

	protected void writeAbort() throws IOException {
		try {
			session.writeOperation(OBEXOperationCodes.ABORT, null);
			byte[] b = session.readOperation();
			HeaderSet dataHeaders = OBEXHeaderSetImpl.readHeaders(b[0], b, 3);
			if (dataHeaders.getResponseCode() != OBEXOperationCodes.OBEX_RESPONSE_SUCCESS) {
				throw new IOException("Fails to abort operation");
			}
		} finally {
			this.isClosed = true;
			closeStream();
		}
	}

	abstract void closeStream() throws IOException;

	protected void validateOperationIsOpen() throws IOException {
		if (isClosed) {
			throw new IOException("operation closed");
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.obex.Operation#getReceivedHeaders()
	 */
	public HeaderSet getReceivedHeaders() throws IOException {
		validateOperationIsOpen();
		endRequestPhase();
		return OBEXHeaderSetImpl.cloneHeaders(this.replyHeaders);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.obex.Operation#getResponseCode()
	 * 
	 * A call will do an implicit close on the Stream and therefore signal that
	 * the request is done.
	 */
	public int getResponseCode() throws IOException {
		validateOperationIsOpen();
		endRequestPhase();
		closeStream();
		return this.replyHeaders.getResponseCode();
	}

	public void sendHeaders(HeaderSet headers) throws IOException {
		if (headers == null) {
			throw new NullPointerException("headers are null");
		}
		OBEXHeaderSetImpl.validateCreatedHeaderSet(headers);
		validateOperationIsOpen();
		if (this.requestEnded) {
			throw new IOException("the request phase has already ended");
		}
		exchangePacket(OBEXHeaderSetImpl.toByteArray(headers));
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.microedition.io.ContentConnection#getEncoding() <code>getEncoding()</code>
	 *      will always return <code>null</code>
	 */
	public String getEncoding() {
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.microedition.io.ContentConnection#getLength() <code>getLength()</code>
	 *      will return the length specified by the OBEX Length header or -1 if
	 *      the OBEX Length header was not included.
	 */
	public long getLength() {
		Long len;
		try {
			len = (Long) replyHeaders.getHeader(HeaderSet.LENGTH);
		} catch (IOException e) {
			return -1;
		}
		if (len == null) {
			return -1;
		}
		return len.longValue();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.microedition.io.ContentConnection#getType() <code>getType()</code>
	 *      will return the value specified in the OBEX Type header or <code>null</code>
	 *      if the OBEX Type header was not included.
	 */
	public String getType() {
		try {
			return (String) replyHeaders.getHeader(HeaderSet.TYPE);
		} catch (IOException e) {
			return null;
		}
	}

	public DataInputStream openDataInputStream() throws IOException {
		return new DataInputStream(openInputStream());
	}

	public DataOutputStream openDataOutputStream() throws IOException {
		return new DataOutputStream(openOutputStream());
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see javax.microedition.io.Connection#close()
	 */
	public void close() throws IOException {
		try {
			endRequestPhase();
		} finally {
			closeStream();
			if (!this.isClosed) {
				this.isClosed = true;
				DebugLog.debug("client operation closed");
			}
		}
	}

	public boolean isClosed() {
		return this.isClosed || this.errorReceived;
	}
}