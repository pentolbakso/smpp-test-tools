package com.a1systems.http.adapter;

import com.a1systems.http.adapter.message.Message;
import com.a1systems.http.adapter.message.MessagePart;
import com.a1systems.http.adapter.sender.SenderTask;
import com.a1systems.http.adapter.sender.SessionHolder;
import com.a1systems.http.adapter.smpp.client.Client;
import com.cloudhopper.smpp.SmppSession;
import com.cloudhopper.smpp.type.RecoverablePduException;
import com.cloudhopper.smpp.type.SmppChannelException;
import com.cloudhopper.smpp.type.SmppTimeoutException;
import com.cloudhopper.smpp.type.UnrecoverablePduException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Application {
    protected static final Logger logger = LoggerFactory.getLogger(Application.class);

    protected ConcurrentHashMap<Long, Message> messages = new ConcurrentHashMap<Long, Message>();

    protected ConcurrentHashMap<Long, MessagePart> messageParts = new ConcurrentHashMap<Long, MessagePart>();

    protected ConcurrentHashMap<String, MessagePart> linkIds = new ConcurrentHashMap<String, MessagePart>();

    protected ConcurrentHashMap<String, Client> smppLinks = new ConcurrentHashMap<String, Client>();

    protected ExecutorService sendPool;

    protected ScheduledExecutorService asyncTaskPool;

    protected IdGenerator messageIdGenerator;
    protected IdGenerator partIdGenerator;

    protected AtomicInteger linkNum = new AtomicInteger(0);

    protected List<Client> clients = new ArrayList<Client>();

    public Application() {
        this.sendPool = Executors.newFixedThreadPool(10);

        this.messageIdGenerator = new IdGenerator(null, 10L);
        this.partIdGenerator = new IdGenerator(null, 10L);
    }

    public ExecutorService getSendPool() {
        return sendPool;
    }

    public void setSendPool(ExecutorService sendPool) {
        this.sendPool = sendPool;
    }

    public ScheduledExecutorService getAsyncTaskPool() {
        return asyncTaskPool;
    }

    public void setAsyncTaskPool(ScheduledExecutorService asyncTaskPool) {
        this.asyncTaskPool = asyncTaskPool;
    }

    public ConcurrentHashMap<String, Client> getSmppLinks() {
        return smppLinks;
    }

    public void setSmppLinks(ConcurrentHashMap<String, Client> smppLinks) {
        this.smppLinks = smppLinks;
    }

    public void start() {
        this.sendPool = Executors.newCachedThreadPool();

        int corePoolSize = 10;

        for (int i=0;i<corePoolSize;i++) {
            this.sendPool.submit(new SenderTask(this));
        }

        for (Client client:this.smppLinks.values()) {
            this.clients.add(client);
        }
    }

    public SessionHolder getMessagePart() {
        int num = this.linkNum.incrementAndGet();

        int linkNumber = num % this.smppLinks.size();

        Client client = this.clients.get(linkNumber);

        if (client.getRateLimiter().tryAcquire()) {
            MessagePart part = client.poll();

            if (part != null) {
                return new SessionHolder(client.getSession(), part);
            }
        }

        return null;
    }

    public String sendMessage(String link, String source, String destination, String message, String encoding) {
        Message msg = new Message(partIdGenerator, source, destination, message, encoding);

        msg.setId(messageIdGenerator.generate());

        Client client = this.getSmppLinks().get(link);

        SmppSession session = client.getSession();

        this.messages.put(msg.getId(), msg);

        for (MessagePart part : msg.getParts()) {
            this.messageParts.put(part.getId(), part);

            client.addToQueue(part);
        }

        return msg.toString();
    }

    public ConcurrentHashMap<Long, Message> getMessages() {
        return messages;
    }

    public ConcurrentHashMap<Long, MessagePart> getMessageParts() {
        return messageParts;
    }

    public ConcurrentHashMap<String, MessagePart> getLinkIds() {
        return linkIds;
    }

    public void setSmscId(String messageId, MessagePart part) {
        part.setSmscId(messageId);

        this.linkIds.put(messageId, part);
    }

}