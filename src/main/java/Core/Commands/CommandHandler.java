package Core.Commands;

import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Contains all the executable commands and handles execution of those commands on demand
 * @author etsubu
 * @version 26 Jul 2018
 *
 */
@Component
public class CommandHandler {
    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);
    private final Map<String, Command> commandMap;

    /**
     * Defines the prefix which a command must begin with
     */
    public static final String COMMAND_PREFIX = "!";
    
    /**
     * Initializes CommandHandler
     */
    public CommandHandler(List<Command> commandList, HelpCommand helpCommand) {
        this.commandMap = new HashMap<>();
        commandList.forEach(x -> x.getNames().forEach(y -> commandMap.put(y, x)));
        helpCommand.getNames().forEach(x -> commandMap.put(x, helpCommand));
        log.info("Initialized command handler");
    }

    /**
     *
     * @param name Name of the command to retrieve
     * @return Command object that has the given name
     */
    public Optional<Command> getCommand(String name) {
        return Optional.ofNullable(commandMap.get(name));
    }
    /**
     * 
     * @param cmd Message typed
     * @return True if the message starts with the command prefix, false if not
     */
    public boolean isCommand(String cmd) {
        return cmd.startsWith(COMMAND_PREFIX);
    }
    
    /**
     * Parses the command name and executes the corresponding command object
     * @param event MessageReceivedEvent that was sent by user
     * @return CommandResult of the command object
     */
    public CommandResult execute(MessageReceivedEvent event) {
        String command = event.getMessage().getContentDisplay();
        if(!command.startsWith(COMMAND_PREFIX) || command.length() == COMMAND_PREFIX.length()) {
            return new CommandResult("Command did not start with prefix '" + COMMAND_PREFIX + "'", false);
        }
        int index = command.indexOf(" ");
        index = (index == -1) ? command.length() : index;
        String parsed = command.substring(COMMAND_PREFIX.length(), index).toLowerCase();
        Command cmd = this.commandMap.get(parsed);
        if(cmd == null) {
            log.info("Failed to find command for user input: " + command.replaceAll("\n", ""));
            return new CommandResult("Unknown command!", false);
        }
        User user = event.getAuthor();
        Optional<List<Role>> userRoles = Optional.ofNullable(event.getMember()).map(Member::getRoles);
        Guild guild = event.getChannelType() == ChannelType.TEXT ? event.getGuild() : null;
        if(index < command.length() - 1)
            return cmd.execute(command.substring(index + 1), user, userRoles.orElse(new LinkedList<>()), guild);
        return cmd.execute("", user, userRoles.orElse(new LinkedList<>()), guild);
    }
}