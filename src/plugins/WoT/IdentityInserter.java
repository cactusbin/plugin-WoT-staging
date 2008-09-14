/**
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */

package plugins.WoT;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;

import freenet.client.ClientMetadata;
import freenet.client.HighLevelSimpleClient;
import freenet.client.InsertBlock;
import freenet.client.InsertException;
import freenet.keys.FreenetURI;
import freenet.support.Logger;
import freenet.support.api.Bucket;
import freenet.support.io.TempBucketFactory;

/**
 * @author Julien Cornuwel (batosai@freenetproject.org)
 *
 */
public class IdentityInserter implements Runnable {
	
	ObjectContainer db;
	HighLevelSimpleClient client;
	final TempBucketFactory tBF;
	
	boolean isRunning;
	
	public IdentityInserter(ObjectContainer db, HighLevelSimpleClient client, TempBucketFactory tbf) {
		this.db = db;
		this.client = client;
		isRunning = true;
		tBF = tbf;
	}
	
	public void run() {
		try{
			Thread.sleep(30 * 1000); // Let the node start up (30 seconds)
		} catch (InterruptedException e){}
		while(isRunning) {
			ObjectSet<OwnIdentity> identities = OwnIdentity.getAllOwnIdentities(db);
			while(identities.hasNext()) {
				OwnIdentity identity = identities.next();
				if(identity.needsInsert()) {
					try {
						Logger.debug(this, "Starting insert of "+identity.getNickName() + " (" + identity.getInsertURI().toString() + ")");
						insert(identity);
						// We set the date now, so if the identity is modified during the insert, we'll insert it again next time
						identity.setLastInsert(new Date()); 
						db.store(identity);
					} catch (Exception e) {
						Logger.error(this, "Identity insert failed: "+e.getMessage(), e);
					}
				}
			}
			db.commit();
			try{
				Thread.sleep(30 * 60 * 1000); // 30 minutes
			} catch (InterruptedException e){}
		}
	}
	
	public void stop() {
		isRunning = false;
		Logger.debug(this, "Stopping IdentityInserter thread");
	}
	
	public void insert(OwnIdentity identity) throws TransformerConfigurationException, FileNotFoundException, ParserConfigurationException, TransformerException, IOException, Db4oIOException, DatabaseClosedException, InvalidParameterException, InsertException {

		Bucket tempB = tBF.makeBucket(1);
		OutputStream os = tempB.getOutputStream();
		FreenetURI iURI;
		try {
			// Create XML file to insert
			identity.exportToXML(db, os);
		
			os.close();
			tempB.setReadOnly();
		
			// Prepare the insert
			ClientMetadata cmd = new ClientMetadata("text/xml");
			InsertBlock ib = new InsertBlock(tempB,cmd,identity.getInsertURI());

			// Logging
			Logger.debug(this, "Started insert of identity '" + identity.getNickName() + "'");

			// Blocking Insert
			iURI = client.insert(ib, false, "identity.xml");
		} finally {
			tempB.free();		
		}
		
		identity.setEdition(iURI.getSuggestedEdition());
		identity.setLastInsert(new Date());
		
		db.store(identity);
		db.commit();
		
		// Logging
		Logger.debug(this, "Successful insert of identity '" + identity.getNickName() + "'");
	}
}