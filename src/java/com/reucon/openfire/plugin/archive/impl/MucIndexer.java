/*
 * Copyright (C) 2008 Jive Software, 2024-2025 Ignite Realtime Foundation. All rights reserved.
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
package com.reucon.openfire.plugin.archive.impl;

import org.apache.lucene.document.*;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.jivesoftware.database.DbConnectionManager;
import org.jivesoftware.openfire.archive.ConversationManager;
import org.jivesoftware.openfire.archive.MonitoringConstants;
import org.jivesoftware.openfire.index.LuceneIndexer;
import org.jivesoftware.openfire.reporting.util.TaskEngine;
import org.jivesoftware.util.JiveGlobals;
import org.jivesoftware.util.StringUtils;
import org.xmpp.packet.JID;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/**
 * Creates and maintains a Lucene index for messages exchanged in multi-user chat.
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 */
public class MucIndexer extends LuceneIndexer
{
    /**
     * The version of the structure that is stored in the Lucene index. When this value differs from the value that is
     * stored in a file with the index, then upon restart, an automatic re-indexation will occur.
     */
    public static final int SCHEMA_VERSION = 1;

    public static final String ALL_MUC_MESSAGES = "SELECT roomID, sender, logTime, body, messageID FROM ofMucConversationLog WHERE messageID IS NOT NULL";
    public static final String NEW_MUC_MESSAGES = "SELECT roomID, sender, logTime, body, messageID FROM ofMucConversationLog WHERE messageID IS NOT NULL AND logTime > ?";

    private ConversationManager conversationManager;

    /**
     * A collection of rooms that are to be removed from the index during the next update or rebuild operation.
     */
    private final Set<Long> roomsPendingDeletion = new HashSet<>();

    public MucIndexer( final TaskEngine taskEngine, final ConversationManager conversationManager )
    {
        super(taskEngine, JiveGlobals.getHomePath().resolve(Path.of(MonitoringConstants.NAME, "mucsearch")), "MUCSEARCH", SCHEMA_VERSION);
        this.conversationManager = conversationManager;
    }

    /**
     * Schedules documents that relate to the provided room for deletion during the next update cycle.
     *
     * @param roomID Room for which documents are to be removed from the index.
     */
    public void scheduleForDeletion(final Long roomID)
    {
        roomsPendingDeletion.add(roomID);
    }

    @Override
    protected Instant doUpdateIndex( final IndexWriter writer, final Instant lastModified ) throws IOException
    {
        // Do nothing if room archiving is disabled.
        if ( !conversationManager.isRoomArchivingEnabled() ) {
            return lastModified;
        }

        if ( lastModified.equals(Instant.EPOCH)) {
            Log.warn( "Updating (not creating) an index since 'EPOCH'. This is suspicious, as it suggests that an existing, but empty index is being operated on. If the index is non-empty, index duplication might occur." );
        }

        // Index MUC messages that arrived since the provided date.
        Log.debug("... started to index MUC messages since {} to update the Lucene index.", lastModified);
        final Instant newestDate = indexMUCMessages(writer, lastModified);
        Log.debug("... finished indexing MUC messages to update the Lucene index. Last indexed message date: {}", newestDate);
        return newestDate;
    }

    @Override
    public Instant doRebuildIndex( final IndexWriter writer ) throws IOException
    {
        // Do nothing if room archiving is disabled.
        if (!conversationManager.isRoomArchivingEnabled()) {
            return Instant.EPOCH;
        }

        // Index all MUC messages.
        Log.debug("... started to index MUC messages to rebuild the Lucene index.");
        final Instant newestDate = indexMUCMessages(writer, Instant.EPOCH);
        Log.debug("... finished indexing MUC messages to update the Lucene index. Lasted indexed message date {}", newestDate);
        return newestDate;
    }

    /**
     * Returns all identifiers of MUC messages in the system.
     *
     * @return A set of message identifiers. Possibly empty, never null.
     */
    private Instant indexMUCMessages( IndexWriter writer, Instant since )
    {
        Instant latest = since;

        Connection con = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            // This retrieves _all_ table content, and operates on the entire result set. For large data sets, one would
            // expect issues caused by the entire data set being loaded into memory, before being operated on.
            // However, with 'fetch size' hint as coded below, this didn't appear to cause any issues on
            // our Igniterealtime.org domain. This domain had over 300,000 messages at the time, had a Java heap that
            // was not larger than 1GB, and uses PostgreSQL 11.5. At the time of the test, it was running Openfire 4.5.0.
            // The entire process took under 8 seconds.
            // Preventing the driver to collect all results at once depends on auto-commit from being disabled, at
            // least for postgres. Getting a 'transaction' connection will ensure this (if supported).
            // MSSQL differentiates between client-cursored and server-cursored result sets. For server-cursored result
            // sets, the fetch buffer and scroll window are the same size (as opposed to fetch buffer containing all
            // the rows). To hint that a server-cursored result set is desired, it should be configured to be 'forward
            // only' as well as 'read only'.
            con = DbConnectionManager.getTransactionConnection();

            if ( since.equals( Instant.EPOCH ) ) {
                pstmt = con.prepareStatement(ALL_MUC_MESSAGES, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            } else {
                pstmt = con.prepareStatement(NEW_MUC_MESSAGES, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                pstmt.setString(1, StringUtils.dateToMillis(Date.from(since))); // This mimics org.jivesoftware.openfire.muc.spi.MUCPersistenceManager.saveConversationLogBatch
            }

            pstmt.setFetchSize(250);
            pstmt.setFetchDirection(ResultSet.FETCH_FORWARD);
            rs = pstmt.executeQuery();

            long progress = 0;
            Instant lastProgressReport = Instant.now();
            while (rs.next()) {
                final long roomID = rs.getLong("roomID");
                final long messageID = rs.getLong("messageID");
                final JID sender;
                try {
                    sender = new JID(rs.getString("sender"));
                } catch (IllegalArgumentException ex) {
                    Log.debug("Invalid JID value for roomID {}, messageID {}.", roomID, messageID, ex);
                    continue;
                }
                final Instant logTime = Instant.ofEpochMilli( Long.parseLong( rs.getString("logTime") ));
                final String body = DbConnectionManager.getLargeTextField(rs, 4);

                // This shouldn't happen, but I've seen a very small percentage of rows have a null body.
                if ( body == null ) {
                    continue;
                }

                // Skip rooms that are going to be deleted anyway.
                if (roomsPendingDeletion.contains(roomID)) {
                    continue;
                }

                // Index message.
                final Document document = createDocument(roomID, messageID, sender, logTime, body );
                writer.addDocument(document);

                if (logTime.isAfter(latest)) {
                    latest = logTime;
                }

                // When there are _many_ messages to be processed, log an occasional progress indicator, to let admins know that things are still churning.
                ++progress;
                if ( lastProgressReport.isBefore( Instant.now().minus(10, ChronoUnit.SECONDS)) ) {
                    Log.debug( "... processed {} messages so far.", progress);
                    lastProgressReport = Instant.now();
                }
            }
            Log.debug( "... finished the entire result set. Processed {} messages in total.", progress );

            if (!since.equals( Instant.EPOCH ) && !roomsPendingDeletion.isEmpty()) {
                // In case this is an update instead of a rebuild, older documents may still refer to rooms that are deleted. Remove those.
                Log.debug( "... removing documents for {} rooms that are pending deletion.", roomsPendingDeletion.size());
                for (long roomID : roomsPendingDeletion) {
                    writer.deleteDocuments(new Term("roomID", Long.toString(roomID)));
                }
            }
            roomsPendingDeletion.clear();
        }
        catch (SQLException sqle) {
            Log.error("An exception occurred while trying to fetch all MUC messages from the database to rebuild the Lucene index.", sqle);
        }
        catch (IOException ex) {
            Log.error("An exception occurred while trying to write the Lucene index.", ex);
        }
        finally {
            DbConnectionManager.closeResultSet(rs);
            DbConnectionManager.closeTransactionConnection(pstmt, con, false); // Only read queries are performed. No need to roll back, even on exceptions.
        }
        return latest;
    }

    /**
     * Creates an index document for one particular chat message.
     *
     * @param roomID ID of the MUC room in which the message was exchanged.
     * @param messageID ID of the message that was exchanged.
     * @param sender Bare or full JID of the author of the message (cannot be null).
     * @param logTime Timestamp of the message (cannot be null).
     * @param body Message text (cannot be null).
     */
    protected static Document createDocument( long roomID, long messageID, JID sender, Instant logTime, String body)
    {
        final Document document = new Document();
        document.add(new LongPoint("roomID", roomID ) );
        document.add(new StoredField("messageID", messageID ) );
        document.add(new NumericDocValuesField("messageIDRange", messageID));
        document.add(new StringField("senderBare", sender.toBareJID(), Field.Store.NO));
        if ( sender.getResource() != null ) {
            document.add(new StringField("senderResource", sender.getResource(), Field.Store.NO));
        }
        document.add(new NumericDocValuesField("logTime", logTime.toEpochMilli()));
        document.add(new TextField("body", body, Field.Store.NO));
        return document;
    }
}
