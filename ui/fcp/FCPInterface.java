/* This code is part of Freenet. It is distributed under the GNU General
 * Public License, version 2 (or at your option any later version). See
 * http://www.gnu.org/ for further details of the GPL. */
package plugins.WoT.ui.fcp;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Iterator;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;

import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.Score;
import plugins.WoT.Trust;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.DuplicateIdentityException;
import plugins.WoT.exceptions.DuplicateScoreException;
import plugins.WoT.exceptions.DuplicateTrustException;
import plugins.WoT.exceptions.InvalidParameterException;
import plugins.WoT.exceptions.NotInTrustTreeException;
import plugins.WoT.exceptions.NotTrustedException;
import plugins.WoT.exceptions.UnknownIdentityException;
import plugins.WoT.introduction.IntroductionPuzzle;
import plugins.WoT.introduction.IntroductionServer;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.ext.DatabaseClosedException;
import com.db4o.ext.Db4oIOException;

import freenet.client.FetchException;
import freenet.client.InsertException;
import freenet.node.FSParseException;
import freenet.pluginmanager.FredPluginFCP;
import freenet.pluginmanager.PluginNotFoundException;
import freenet.pluginmanager.PluginReplySender;
import freenet.support.Logger;
import freenet.support.SimpleFieldSet;
import freenet.support.api.Bucket;

/**
 * @author xor
 */
public final class FCPInterface implements FredPluginFCP {

    private final WoT mWoT;
    private final ObjectContainer db;

    public FCPInterface(final WoT myWoT) {
        mWoT = myWoT;
        db = mWoT.getDB();
    }

    public void handle(final PluginReplySender replysender, final SimpleFieldSet params, final Bucket data, final int accesstype) {

        try {
            final String message = params.get("Message");
            if (message.equals("CreateIdentity")) {
                replysender.send(handleCreateIdentity(params), data);
            } else if (message.equals("SetTrust")) {
                replysender.send(handleSetTrust(params), data);
            } else if (message.equals("AddIdentity")) {
                replysender.send(handleAddIdentity(params), data);
            } else if (message.equals("GetIdentity")) {
                replysender.send(handleGetIdentity(params), data);
            } else if (message.equals("GetOwnIdentities")) {
                replysender.send(handleGetOwnIdentities(params), data);
            } else if (message.equals("GetIdentitiesByScore")) {
                replysender.send(handleGetIdentitiesByScore(params), data);
            } else if (message.equals("GetTrusters")) {
                replysender.send(handleGetTrusters(params), data);
            } else if (message.equals("GetTrustees")) {
                replysender.send(handleGetTrustees(params), data);
            } else if (message.equals("AddContext")) {
                replysender.send(handleAddContext(params), data);
            } else if (message.equals("RemoveContext")) {
                replysender.send(handleRemoveContext(params), data);
            } else if (message.equals("SetProperty")) {
                replysender.send(handleSetProperty(params), data);
            } else if (message.equals("GetProperty")) {
                replysender.send(handleGetProperty(params), data);
            } else if (message.equals("RemoveProperty")) {
                replysender.send(handleRemoveProperty(params), data);
            } else {
                throw new Exception("Unknown message (" + message + ")");
            }
        } catch (final Exception e) {
            Logger.error(this, e.toString());
            try {
                replysender.send(errorMessageFCP(params.get("Message"), e), data);
            } catch (final PluginNotFoundException e1) {
                Logger.normal(this, "Connection to request sender lost", e1);
            }
        }
    }

    private SimpleFieldSet handleCreateIdentity(final SimpleFieldSet params)
    throws TransformerConfigurationException,
    FileNotFoundException, InvalidParameterException, ParserConfigurationException, TransformerException,
    IOException, InsertException, Db4oIOException, DatabaseClosedException, DuplicateScoreException,
    NotTrustedException, DuplicateTrustException, FSParseException
    {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        OwnIdentity identity;

        if (params.get("NickName") == null
                || params.get("PublishTrustList") == null
                || params.get("Context") == null)
        {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        if (params.get("RequestURI") == null || params.get("InsertURI") == null) {
            identity = mWoT.createIdentity(params.get("NickName"), params.getBoolean("PublishTrustList"), params
                    .get("Context"));
        } else {
            identity = mWoT.createIdentity(
                    params.get("InsertURI"),
                    params.get("RequestURI"),
                    params.get("NickName"),
                    params.getBoolean("PublishTrustList"),
                    params.get("Context"));
        }

        /*
         * TODO: Publishing introduction puzzles makes no sense if the identity does not publish the trust list. We
         * should warn the user if we receive PublishTrustList == false and PublishIntroductionPuzzles == true
         */

        if (params.getBoolean("PublishTrustList")
                && params.get("PublishIntroductionPuzzles") != null
                && params.getBoolean("PublishIntroductionPuzzles"))
        {
            /* TODO: Create a function for those? */
            identity.addContext(db, IntroductionPuzzle.INTRODUCTION_CONTEXT);
            identity.setProperty(db, "IntroductionPuzzleCount", Integer.toString(IntroductionServer.PUZZLE_COUNT));
        }

        sfs.putAppend("Message", "IdentityCreated");
        sfs.putAppend("ID", identity.getId());
        sfs.putAppend("InsertURI", identity.getInsertURI().toString());
        sfs.putAppend("RequestURI", identity.getRequestURI().toString());
        return sfs;
    }

    private SimpleFieldSet handleSetTrust(final SimpleFieldSet params)
    throws NumberFormatException,
    TransformerConfigurationException, FileNotFoundException, InvalidParameterException,
    ParserConfigurationException, TransformerException, IOException, InsertException, UnknownIdentityException,
    Db4oIOException, DatabaseClosedException, DuplicateScoreException, DuplicateIdentityException,
    NotTrustedException, DuplicateTrustException
    {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("Truster") == null
                || params.get("Trustee") == null
                || params.get("Value") == null
                || params.get("Comment") == null)
        {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        mWoT.setTrust(
                params.get("Truster"),
                params.get("Trustee"),
                params.get("Value"),
                params.get("Comment"));

        sfs.putAppend("Message", "TrustSet");
        return sfs;
    }

    private SimpleFieldSet handleAddIdentity(final SimpleFieldSet params)
    throws InvalidParameterException,
    MalformedURLException, FetchException, DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("RequestURI") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        final Identity identity = mWoT.addIdentity(params.get("RequestURI").trim());

        sfs.putAppend("Message", "IdentityAdded");
        sfs.putAppend("RequestURI", identity.getRequestURI().toString());
        return sfs;
    }

    private SimpleFieldSet handleGetIdentity(final SimpleFieldSet params)
    throws InvalidParameterException,
    MalformedURLException, FetchException, UnknownIdentityException, DuplicateScoreException,
    DuplicateIdentityException, DuplicateTrustException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("TreeOwner") == null || params.get("Identity") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        sfs.putAppend("Message", "Identity");

        final OwnIdentity treeOwner = OwnIdentity.getByURI(db, params.get("TreeOwner"));
        final Identity identity = Identity.getByURI(db, params.get("Identity"));

        try {
            final Trust trust = identity.getReceivedTrust(treeOwner, db);
            sfs.putAppend("Trust", String.valueOf(trust.getValue()));
        } catch (final NotTrustedException e1) {
            sfs.putAppend("Trust", "null");
        }

        Score score;
        try {
            score = identity.getScore(treeOwner, db);
            sfs.putAppend("Score", String.valueOf(score.getScore()));
            sfs.putAppend("Rank", String.valueOf(score.getRank()));
        } catch (final NotInTrustTreeException e) {
            sfs.putAppend("Score", "null");
            sfs.putAppend("Rank", "null");
        }

        final Iterator<String> contexts = identity.getContexts().iterator();
        for (int i = 1; contexts.hasNext(); i++) {
            sfs.putAppend("Context" + i, contexts.next());
        }

        return sfs;
    }

    private SimpleFieldSet handleGetOwnIdentities(final SimpleFieldSet params)
    throws InvalidParameterException,
    MalformedURLException, UnknownIdentityException, DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        sfs.putAppend("Message", "OwnIdentities");

        final ObjectSet<OwnIdentity> result = OwnIdentity.getAllOwnIdentities(db);

        for (int idx = 1; result.hasNext(); idx++) {
            final OwnIdentity oid = result.next();
            /* FIXME: Isn't append slower than replace? Figure this out */
            sfs.putAppend("Identity" + idx, oid.getId());
            sfs.putAppend("RequestURI" + idx, oid.getRequestURI().toString());
            sfs.putAppend("InsertURI" + idx, oid.getInsertURI().toString());
            sfs.putAppend("Nickname" + idx, oid.getNickName());
            /* FIXME: Allow the client to select what data he wants */
        }
        return sfs;
    }

    private SimpleFieldSet handleGetIdentitiesByScore(final SimpleFieldSet params)
    throws InvalidParameterException,
    MalformedURLException, UnknownIdentityException, DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("Select") == null || params.get("Context") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        sfs.putAppend("Message", "Identities");

        final OwnIdentity treeOwner = params.get("TreeOwner") != null ? OwnIdentity.getByURI(db, params.get("TreeOwner"))
                : null;

        final String selectString = params.get("Select").trim();
        int select = 0; // TODO: decide about the default value

        if (selectString.equals("+")) {
            select = 1;
        } else if (selectString.equals("-")) {
            select = -1;
        } else if (selectString.equals("0")) {
            select = 0;
        } else {
            throw new InvalidParameterException("Unhandled select value (" + select + ")");
        }

        final ObjectSet<Score> result = Score.getIdentitiesByScore(db, treeOwner, select);
        final String context = params.get("Context");
        final boolean getAll = context.equals("all");

        for (int idx = 1; result.hasNext();) {
            final Score score = result.next();
            // TODO: Maybe there is a way to do this through SODA
            if (getAll || score.getTarget().hasContext(context)) {
                final Identity id = score.getTarget();
                /* FIXME: Isn't append slower than replace? Figure this out */
                sfs.putAppend("Identity" + idx, id.getId());
                sfs.putAppend("RequestURI" + idx, id.getRequestURI().toString());
                sfs.putAppend("Nickname" + idx, id.getNickName() != null ? id.getNickName() : "");
                ++idx;
                /* FIXME: Allow the client to select what data he wants */
            }
        }
        return sfs;
    }

    private SimpleFieldSet handleGetTrusters(final SimpleFieldSet params)
    throws InvalidParameterException,
    MalformedURLException, UnknownIdentityException, Db4oIOException, DatabaseClosedException,
    DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("Identity") == null || params.get("Context") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        sfs.putAppend("Message", "Identities");

        final ObjectSet<Trust> result = Identity.getByURI(db, params.get("Identity")).getReceivedTrusts(db);

        for (int i = 1; result.hasNext(); i++) {
            final Trust trust = result.next();
            // Maybe there is a way to do this through SODA
            if (trust.getTruster().hasContext(params.get("Context")) || params.get("Context").equals("all")) {
                sfs.putAppend("Identity" + i, trust.getTruster().getRequestURI().toString());
                sfs.putAppend("Value" + i, String.valueOf(trust.getValue()));
                sfs.putAppend("Comment" + i, trust.getComment());
            }
        }
        return sfs;
    }

    private SimpleFieldSet handleGetTrustees(final SimpleFieldSet params)
    throws InvalidParameterException,
    MalformedURLException, UnknownIdentityException, Db4oIOException, DatabaseClosedException,
    DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("Identity") == null || params.get("Context") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        sfs.putAppend("Message", "Identities");

        final ObjectSet<Trust> result = Identity.getByURI(db, params.get("Identity")).getGivenTrusts(db);

        for (int i = 1; result.hasNext(); i++) {
            final Trust trust = result.next();
            // Maybe there is a way to do this through SODA
            if (trust.getTrustee().hasContext(params.get("Context")) || params.get("Context").equals("all")) {
                sfs.putAppend("Identity" + i, trust.getTrustee().getRequestURI().toString());
                sfs.putAppend("Value" + i, String.valueOf(trust.getValue()));
                sfs.putAppend("Comment" + i, trust.getComment());
            }
        }
        return sfs;
    }

    private SimpleFieldSet handleAddContext(final SimpleFieldSet params) throws InvalidParameterException,
    MalformedURLException, UnknownIdentityException, DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("Identity") == null || params.get("Context") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        mWoT.addContext(params.get("Identity"), params.get("Context"));

        sfs.putAppend("Message", "ContextAdded");
        return sfs;
    }

    private SimpleFieldSet handleRemoveContext(final SimpleFieldSet params) throws InvalidParameterException,
    MalformedURLException, UnknownIdentityException, DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("Identity") == null || params.get("Context") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        mWoT.removeContext(params.get("Identity"), params.get("Context"));

        sfs.putAppend("Message", "ContextRemoved");
        return sfs;
    }

    private SimpleFieldSet handleSetProperty(final SimpleFieldSet params) throws InvalidParameterException,
    MalformedURLException, UnknownIdentityException, DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("Identity") == null || params.get("Property") == null || params.get("Value") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        mWoT.setProperty(params.get("Identity"), params.get("Property"), params.get("Value"));

        sfs.putAppend("Message", "PropertyAdded");
        return sfs;
    }

    private SimpleFieldSet handleGetProperty(final SimpleFieldSet params) throws InvalidParameterException,
    MalformedURLException, UnknownIdentityException, DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("Identity") == null || params.get("Property") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        sfs.putAppend("Message", "PropertyValue");
        sfs.putAppend("Property", mWoT.getProperty(params.get("Identity"), params.get("Property")));

        return sfs;
    }

    private SimpleFieldSet handleRemoveProperty(final SimpleFieldSet params) throws InvalidParameterException,
    MalformedURLException, UnknownIdentityException, DuplicateIdentityException {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);

        if (params.get("Identity") == null || params.get("Property") == null) {
            throw new InvalidParameterException("Missing mandatory parameter");
        }

        mWoT.removeProperty(params.get("Identity"), params.get("Property"));

        sfs.putAppend("Message", "PropertyRemoved");
        return sfs;
    }

    private SimpleFieldSet errorMessageFCP(final String originalMessage, final Exception e) {

        final SimpleFieldSet sfs = new SimpleFieldSet(true);
        sfs.putAppend("Message", "Error");
        sfs.putAppend("OriginalMessage", originalMessage);
        sfs.putAppend("Description", (e.getLocalizedMessage() == null) ? "null" : e.getLocalizedMessage());
        e.printStackTrace();
        return sfs;
    }
}