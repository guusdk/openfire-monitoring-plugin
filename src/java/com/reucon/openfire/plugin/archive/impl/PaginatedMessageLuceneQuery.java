package com.reucon.openfire.plugin.archive.impl;

import com.reucon.openfire.plugin.archive.model.ArchivedMessage;
import com.reucon.openfire.plugin.archive.xep0313.IQQueryHandler;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.archive.MonitoringConstants;
import org.jivesoftware.openfire.plugin.MonitoringPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * Encapsulates responsibility of creating a Lucene query that retrieves a specific subset (page) of archived messages
 * from a specific end-user entity owner (the archive that's queried is considered to be a 'personal archive').
 *
 * @author Guus der Kinderen, guus.der.kinderen@gmail.com
 */
public class PaginatedMessageLuceneQuery extends AbstractPaginatedMamQuery
{
    private static final Logger Log = LoggerFactory.getLogger( PaginatedMessageLuceneQuery.class );

    /**
     * Creates a query for messages from a message archive.
     *
     * @param startDate Start (inclusive) of period for which to return messages. EPOCH will be used if no value is provided.
     * @param endDate End (inclusive) of period for which to return messages. 'now' will be used if no value is provided.
     * @param archiveOwner The message archive owner.
     * @param with An optional conversation partner
     * @param query A search string to be used for text-based search.
     */
    public PaginatedMessageLuceneQuery(@Nullable final Date startDate, @Nullable final Date endDate, @Nonnull final JID archiveOwner, @Nullable final JID with, @Nonnull final String query)
    {
        super(startDate, endDate, archiveOwner, with, query);
    }

    protected IndexSearcher getSearcher() throws IOException
    {
        final MonitoringPlugin plugin = (MonitoringPlugin) XMPPServer.getInstance().getPluginManager().getPluginByName(MonitoringConstants.PLUGIN_NAME).get();
        final MessageIndexer archiveIndexer = plugin.getMessageIndexer();
        final IndexSearcher searcher = archiveIndexer.getSearcher();
        return searcher;
    }

    @Override
    public List<ArchivedMessage> getPage(final Long after, final Long before, final int maxResults, final boolean isPagingBackwards) throws DataRetrievalException {
        Log.debug( "Retrieving archived messages page. After: {}, Before: {}, maxResults: {}, isPagingBackwards: {}", after, before, maxResults, isPagingBackwards);
        final List<ArchivedMessage> result = new ArrayList<>();
        try
        {
            final IndexSearcher searcher = getSearcher();
            final Query query = getLuceneQueryForPage(after, before);
            final TopFieldDocs indexResult = searcher.search(query, maxResults, getSort(isPagingBackwards));

            for ( final ScoreDoc scoreDoc : indexResult.scoreDocs )
            {
                final Document doc = searcher.doc(scoreDoc.doc);
                final long messageID = Long.parseLong(doc.get("messageID"));
                final ArchivedMessage archivedMessage = JdbcPersistenceManager.getArchivedMessage(messageID, archiveOwner);
                if ( archivedMessage != null ) {
                    result.add( archivedMessage );
                }
            }

            // The order of items in the page must always be chronologically, oldest to newest, even when paging backwards.
            if ( isPagingBackwards ) {
                Collections.reverse( result );
            }
        }
        catch ( Exception e ) {
            Log.warn( "An exception occurred while trying to query the Lucene index to get messages from archive of owner {}.", archiveOwner, e );
            if (!IQQueryHandler.IGNORE_RETRIEVAL_EXCEPTIONS.getValue()) {
                throw new DataRetrievalException(e);
            }
        }
        Log.debug( "Returning {} result(s).", result.size() );
        return result;
    }

    /**
     * Returns the amount of messages that are in the entire, unlimited/unpaged, result set.
     *
     * @return A message count, or -1 if unavailable.
     */
    @Override
    public int getTotalCount() {
        try
        {
            final Query query = getLuceneQueryForAllResults();
            final IndexSearcher searcher = getSearcher();
            final TotalHitCountCollector collector = new TotalHitCountCollector();
            searcher.search( query, collector );
            final int result = collector.getTotalHits();
            Log.debug( "Total number for unpaged query is: {}. Query: {}", result, query );

            return result;
        }
        catch ( Exception e )
        {
            Log.warn( "An exception occurred while trying to get a count of messages that match a query for message from archive of owner {}.", archiveOwner, e );
            return -1;
        }
    }

    protected Query getLuceneQueryForAllResults() throws ParseException
    {
        final StandardAnalyzer analyzer = new StandardAnalyzer();

        final BooleanQuery.Builder builder = new BooleanQuery.Builder();

        // Create the query based on the search terms.
        final Query textQuery = new QueryParser("body", analyzer).parse( QueryParser.escape(query) );
        builder.add(textQuery, BooleanClause.Occur.MUST );

        // Limit to the owner of the archive.
        builder.add(new TermQuery(new Term("owner", archiveOwner.toBareJID() ) ), BooleanClause.Occur.MUST );

        // Limit potential results to the requested time range. Note that these values are always non-null in this method (might be 'EPOCH' though).
        final Query dateRangeQuery = NumericDocValuesField.newSlowRangeQuery("sentDate", startDate.getTime(), endDate.getTime());
        builder.add(dateRangeQuery, BooleanClause.Occur.MUST);

        // If defined, limit to specific senders.
        if ( with != null ) {
            // Always limit to the bare JID of the sender.
            builder.add(new TermQuery(new Term("withBare", with.toBareJID() ) ), BooleanClause.Occur.MUST );

            // If the query specified a more specific full JID, include the resource part in the filter too.
            if ( with.getResource() != null ) {
                builder.add(new TermQuery( new Term( "withResource", with.getResource() ) ), BooleanClause.Occur.MUST );
            }
        }

        final BooleanQuery query = builder.build();
        Log.debug( "Constructed all-result query: {}", query);
        return query;
    }

    protected Query getLuceneQueryForPage( final Long after, final Long before ) throws ParseException
    {
        final BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(getLuceneQueryForAllResults(), BooleanClause.Occur.MUST );

        // Limit by 'before' and 'after', if applicable.
        if ( after != null || before != null) {
            builder.add(NumericDocValuesField.newSlowRangeQuery( "messageIDRange",
                                                 after != null ? Math.addExact(after, 1) : Long.MIN_VALUE,
                                                 before != null ? Math.addExact(before, -1) : Long.MAX_VALUE
            ), BooleanClause.Occur.MUST );
        }

        final BooleanQuery query = builder.build();
        Log.debug( "Constructed page-result query: {}", query);
        return query;
    }

    public Sort getSort( final boolean isPagingBackwards ) {
        // Always sort based on date.
        return new Sort(new SortField("sentDate", SortField.Type.LONG, isPagingBackwards));
    }

    @Override
    public String toString()
    {
        return "PaginatedMessageLuceneQuery{" +
            "startDate=" + startDate +
            ", endDate=" + endDate +
            ", owner=" + archiveOwner +
            ", with=" + with +
            ", query='" + query + '\'' +
            '}';
    }
}
