package com.etsubu.stonksbot.discord;

import com.etsubu.stonksbot.administrative.GuildRoleManager;
import com.etsubu.stonksbot.command.CommandResult;
import com.etsubu.stonksbot.configuration.ConfigLoader;
import com.etsubu.stonksbot.scheduler.omxnordic.Model.AttachmentFile;
import com.etsubu.stonksbot.command.CommandHandler;
import com.etsubu.stonksbot.configuration.Config;
import com.etsubu.stonksbot.permission.PermissionManager;
import com.etsubu.stonksbot.utility.MessageUtils;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.guild.GenericGuildEvent;
import net.dv8tion.jda.api.events.message.GenericMessageEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.message.MessageUpdateEvent;
import net.dv8tion.jda.api.events.role.RoleCreateEvent;
import net.dv8tion.jda.api.events.role.RoleDeleteEvent;
import net.dv8tion.jda.api.events.role.update.RoleUpdatePositionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.restaction.MessageAction;
import net.dv8tion.jda.internal.handle.GuildRoleUpdateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import javax.security.auth.login.LoginException;
import java.util.List;
import java.util.Optional;

/**
 * Handles incoming discord events and messages
 *
 * @author etsubu
 * @version 26 Jul 2018
 */
@Component
public final class EventCore extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(EventCore.class);
    public static final String SERVER_ID = "server_id";
    public static final String CHANNEL_ID = "channel_id";
    public static final String USER_ID = "user_id";
    private final CommandHandler commandHandler;
    private JDA jda;
    private final PermissionManager permissionManager;
    private final MessageReacter reacter;
    private final FilterHandler filterHandler;
    private final ConfigLoader configLoader;
    private final GuildRoleManager guildRoleManager;

    /**
     * Initializes EventCore
     */
    public EventCore(CommandHandler commandHandler,
                     ConfigLoader configLoader,
                     PermissionManager permissionManager,
                     MessageReacter reacter,
                     FilterHandler filterHandler,
                     GuildRoleManager guildRoleManager) {
        this.configLoader = configLoader;
        this.commandHandler = commandHandler;
        this.permissionManager = permissionManager;
        this.reacter = reacter;
        this.filterHandler = filterHandler;
        this.guildRoleManager = guildRoleManager;
        new Thread(this::start).start();
        log.info("Initialized {}", this.getClass().getName());
    }

    public boolean start() {
        if (jda != null) {
            jda.shutdown();
        }
        Config config = configLoader.getConfig();
        Optional<String> oauth = Optional.ofNullable(config.getOauth());
        if (oauth.isEmpty()) {
            log.error("No oath token present");
            return false;
        }
        try {
            this.jda = JDABuilder.createDefault(oauth.get()).build();
        } catch (LoginException e) {
            log.error("Login failed, check the network connection and oath token");
            return false;
        }
        jda.addEventListener(this, guildRoleManager);
        log.info("JDA connected");
        return true;
    }

    public void registerJDA(JDA jda) {
        this.jda = jda;
        jda.addEventListener(this);
    }

    /**
     * Sends text message to the requested channel on server
     *
     * @param channelIds
     * @param message
     * @return
     */
    public boolean sendMessage(List<Long> channelIds, String message, List<AttachmentFile> attachmentFiles) {
        boolean sent = false;
        for (long channelId : channelIds) {
            TextChannel textChannel = jda.getTextChannelById(channelId);
            if (textChannel != null) {
                MessageAction msg = textChannel.sendMessage(MessageUtils.cleanMessage(message));
                if (attachmentFiles != null && !attachmentFiles.isEmpty()) {
                    for (AttachmentFile file : attachmentFiles) {
                        msg = msg.addFile(file.getFile(), file.getFilename());
                    }
                }
                msg.queue();
                sent = true;
            } else {
                log.warn("Failed to find text channel with id {}", channelId);
            }
        }
        return sent;
    }

    public void sendPrivateMessage(User user, String content) {
        user.openPrivateChannel().queue((channel) -> channel.sendMessage(content).queue());
    }

    @Override
    public void onMessageUpdate(MessageUpdateEvent event) {
        MDC.put(SERVER_ID, event.getMessage().isFromGuild() ? event.getGuild().getId() : "no_server");
        MDC.put(USER_ID, event.getAuthor().getId());
        MDC.put(CHANNEL_ID, event.getChannel().getId());

        if (event.getAuthor().getId().equalsIgnoreCase(jda.getSelfUser().getId())) {
            // Skip messages sent by ourselves
            return;
        }
        if (filterHandler.shouldBeFiltered(event.getMessage(), event.getMember(), event.getAuthor(), event)) {
            return;
        }
        log.info("modify {}", event.getMessage().getContentRaw());
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        MDC.put(SERVER_ID, event.getMessage().isFromGuild() ? event.getGuild().getId() : "no_server");
        MDC.put(USER_ID, event.getAuthor().getId());
        MDC.put(CHANNEL_ID, event.getChannel().getId());

        if (event.getAuthor().getId().equalsIgnoreCase(jda.getSelfUser().getId())) {
            // Skip messages sent by ourselves
            return;
        }
        reacter.react(event).ifPresent(x -> event.getMessage().addReaction(x).queue());
        if (filterHandler.shouldBeFiltered(event.getMessage(), event.getMember(), event.getAuthor(), event)) {
            return;
        }

        if (this.commandHandler.isCommand(event.getMessage().getContentDisplay()) && event.getMessage().getContentDisplay().length() > 1) {
            if (!permissionManager.isReplyAllowed(event)) {
                log.info("Prevented reply for {}  on channel {}", event.getAuthor().getName(), event.getChannel().getName());
                //event.getChannel().sendMessage("Please use whitelisted channel for performing commands").queue();
                return;
            }
            try {
                CommandResult result = commandHandler.execute(event);
                if (result.getResponse().isEmpty() || result.getResponse().isBlank()) {
                    log.error("Command returned blank response");
                    event.getMessage().reply("Oops, this command returned blank response. Developer should probably take a look at logs.").queue();
                } else if (result.isSucceeded()) {
                    if (result.isRespondWithDM()) {
                        sendPrivateMessage(event.getAuthor(), result.getResponse());
                        event.getMessage().reply("Responded with DM.").queue();
                    } else {
                        event.getMessage().reply(MessageUtils.cleanMessage(result.getResponse())).queue();
                    }
                    log.info("Successfully executed user command: {}", event.getMessage().getContentDisplay().replaceAll("\n", ""));
                } else {
                    event.getMessage().reply(result.getResponse()).queue();
                    log.error("Failed to execute user command: " + result.getResponse() + " - "
                            + Optional.ofNullable(result.getException())
                            .map(Exception::getMessage)
                            .orElse(""));
                }
            } catch (Exception e) {
                log.error("Uncaught exception", e);
                event.getMessage().reply("Oops, this command caused an unexpected exception. Developer should probably take a look at logs.").queue();
            }
        }
    }
}