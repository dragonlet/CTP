/*---------------------------------------------------------------
*  Copyright 2015 by the Radiological Society of North America
*
*  This source software is released under the terms of the
*  RSNA Public License (http://mirc.rsna.org/rsnapubliclicense.pdf)
*----------------------------------------------------------------*/

package org.rsna.ctp.stdstages;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.apache.log4j.Logger;
import org.rsna.ctp.objects.XmlObject;
import org.rsna.ctp.pipeline.AbstractExportService;
import org.rsna.ctp.pipeline.Status;
import org.rsna.util.Base64;
import org.rsna.util.FileUtil;
import org.rsna.util.HttpUtil;
import org.rsna.util.XmlUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * An ExportService that exports XmlObjects to an AIM Data Service.
 */
public class AimExportService extends AbstractExportService {

	static final Logger logger = Logger.getLogger(AimExportService.class);

	URL url;
	String protocol;
	boolean logAll = false;
	boolean logFailed = false;

	String username;
	String password;
	boolean authenticate;
	String authHeader = "";

	/**
	 * Class constructor; creates a new instance of the ExportService.
	 * @param element the configuration element.
	 * @throws Exception if the URL does not parse
	 */
	public AimExportService(Element element) throws Exception {
		super(element);

		//Get the destination url
		url = new URL(element.getAttribute("url").trim());
		protocol = url.getProtocol().toLowerCase();
		if (!protocol.startsWith("https") && !protocol.startsWith("http")) {
			logger.error(name+": Illegal protocol ("+protocol+")");
			throw new Exception();
		}

		//Get the credentials, if any
		username = element.getAttribute("username").trim();
		password = element.getAttribute("password").trim();
		authenticate = !username.equals("");
		if (authenticate) {
			authHeader = "Basic " + Base64.encodeToString((username + ":" + password).getBytes());
		}

		//See if we are to log the responses from the AIM Data Service
		String logResponses = element.getAttribute("logResponses").trim().toLowerCase();
		logAll = logResponses.equals("all");
		logFailed = logResponses.equals("failed");

		//Only accept XmlObjects, no matter what the configuration says.
		acceptDicomObjects = false;
		acceptXmlObjects = true;
		acceptZipObjects = false;
		acceptFileObjects = false;
	}

	/**
	 * Export a file.
	 * @param fileToExport the file to export.
	 * @return the status of the attempt to export the file.
	 */
	public Status export(File fileToExport) {
		HttpURLConnection conn;
		OutputStream svros;
		OutputStreamWriter writer;
		XmlObject xmlObject = null;

		//Parse the file and make sure we can handle it
		try { xmlObject = new XmlObject(fileToExport); }
		catch (Exception invalid) { return Status.FAIL; }

		Document doc = xmlObject.getDocument();
		Element root = doc.getDocumentElement();
		String rootName = root.getTagName();
		if (!rootName.equals("ImageAnnotation")) {
			logger.warn(name+": XmlObject with illegal root ("+rootName+") not transmitted.");
			return Status.FAIL;
		}

		//Get the text.
		String text = XmlUtil.toString( doc.getDocumentElement() );

		//Make the connection and send the text.
		try {
			//Establish the connection
			conn = HttpUtil.getConnection(url);
			conn.setRequestProperty("Content-Type", "text/xml;charset=UTF-8");
			conn.connect();

			//Get a writer for UTF-8
			svros = conn.getOutputStream();
			writer = new OutputStreamWriter( svros, FileUtil.utf8 );

			//Send the text to the server
			writer.write( text, 0, text.length() );
			writer.flush();
			writer.close();

			//Get the response code
			int responseCode = conn.getResponseCode();
			boolean ok = (responseCode == 200);

			//Get the response.
			String response = "";
			try { response = FileUtil.getText( ok ? conn.getInputStream() : conn.getErrorStream() ); }
			catch (Exception is) {
				response = "Unable to obtain response text";
				logger.debug(response, is);
			}

			//Make any necessary log entries.
			if (logAll || (!ok && logFailed)) {
				logger.info(name+": export response code: "+responseCode
									+(response.equals("")?"":"\n"+response));
			}

			//Return the appropriate Status instance
			return (ok ? Status.OK : Status.FAIL);
		}
		catch (Exception e) {
			//This indicates a network failure; log it and set up for a retry.
			logger.warn(name+": export failed: " + e.getMessage());
			logger.debug("Stack trace:", e);
		}
		return Status.RETRY;
	}
}