package com.reucon.openfire.plugin.archive.model;

import org.jivesoftware.database.JiveID;
import org.xmpp.packet.JID;

import java.util.*;

/**
 * A conversation between two or more participants.
 */
@JiveID(602)
public class Conversation
{
    private Long id;
    private final Date start;
    private Date end;
    private final JID owner;
    private final JID with;
    private String subject;
    private final String thread;
    private final List<Participant> participants;
    private final List<ArchivedMessage> messages;

    public Conversation(Date start, JID owner, JID with, String subject, String thread)
    {
        this(start, start, owner, with, subject, thread);
    }

    public Conversation(Date start, Date end, JID owner, JID with, String subject, String thread)
    {
        this.start = start;
        this.end = end;
        this.owner = owner;
        this.with = with;
        this.subject = subject;
        this.thread = thread;
        participants = new ArrayList<>();
        messages = new ArrayList<>();
    }

    public Long getId()
    {
        return id;
    }

    public void setId(Long id)
    {
        this.id = id;
    }

    public Date getStart()
    {
        return start;
    }

    public Date getEnd()
    {
        return end;
    }

    public void setEnd(Date end)
    {
        this.end = end;
    }

    public JID getOwnerBareJid()
    {
        return owner.asBareJID();
    }

    public String getOwnerResource()
    {
        return owner.getResource();
    }

    public JID getWithBareJid()
    {
        return with.asBareJID();
    }

    public String getWithResource()
    {
        return with.getResource();
    }

    public String getSubject()
    {
        return subject;
    }

    public void setSubject(String subject)
    {
        this.subject = subject;
    }

    public String getThread()
    {
        return thread;
    }

    public Collection<Participant> getParticipants()
    {
        return Collections.unmodifiableCollection(participants);
    }

    public void addParticipant(Participant participant)
    {
        synchronized (participants)
        {
            participants.add(participant);
        }
    }

    public List<ArchivedMessage> getMessages()
    {
        return Collections.unmodifiableList(messages);
    }

    public void addMessage(ArchivedMessage message)
    {
        synchronized (messages)
        {
            messages.add(message);
        }
    }

    public boolean isStale(int conversationTimeout)
    {
        Long now = System.currentTimeMillis();

        return end.getTime() + conversationTimeout * 60L * 1000L < now;
    }

    /**
     * Checks if this conversation has an active participant with the given JID.
     *
     * @param jid JID of the participant
     * @return <code>true</code> if this conversation has an active participant with the given JID,
     *         <code>false</code> otherwise.
     */
    public boolean hasParticipant(JID jid)
    {
        synchronized (participants)
        {
            for (Participant p : participants)
            {
                if (p.getJid().equals(jid))
                {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if this conversation is new and has not yet been persisted.
     *
     * @return <code>true</code> if this conversation is new and has not yet been persisted,
     *         <code>false</code> otherwise.
     */
    public boolean isNew()
    {
        return id == null;
    }
}
