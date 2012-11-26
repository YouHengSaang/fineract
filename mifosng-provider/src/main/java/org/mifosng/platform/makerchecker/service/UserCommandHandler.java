package org.mifosng.platform.makerchecker.service;

import org.joda.time.LocalDate;
import org.mifosng.platform.api.commands.UserCommand;
import org.mifosng.platform.api.infrastructure.PortfolioApiDataConversionService;
import org.mifosng.platform.api.infrastructure.PortfolioCommandDeserializerService;
import org.mifosng.platform.api.infrastructure.PortfolioCommandSerializerService;
import org.mifosng.platform.client.service.RollbackTransactionAsCommandIsNotApprovedByCheckerException;
import org.mifosng.platform.makerchecker.domain.CommandSource;
import org.mifosng.platform.security.PlatformSecurityContext;
import org.mifosng.platform.user.domain.AppUser;
import org.mifosng.platform.user.service.AppUserWritePlatformService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class UserCommandHandler implements CommandSourceHandler {

    private final PlatformSecurityContext context;
    private final ChangeDetectionService changeDetectionService;
    private final PortfolioApiDataConversionService apiDataConversionService;
    private final PortfolioCommandSerializerService commandSerializerService;
    private final PortfolioCommandDeserializerService commandDeserializerService;
    private final AppUserWritePlatformService writePlatformService;
    
    @Autowired
    public UserCommandHandler(final PlatformSecurityContext context, final ChangeDetectionService changeDetectionService,
            final PortfolioApiDataConversionService apiDataConversionService,
            final PortfolioCommandSerializerService commandSerializerService,
            final PortfolioCommandDeserializerService commandDeserializerService,
            final AppUserWritePlatformService writePlatformService) {
        this.context = context;
        this.changeDetectionService = changeDetectionService;
        this.apiDataConversionService = apiDataConversionService;
        this.commandSerializerService = commandSerializerService;
        this.commandDeserializerService = commandDeserializerService;
        this.writePlatformService = writePlatformService;
    }

    /*
     * Used when users with 'create' capability create a command. If 'maker-checker' is not
     * enabled for this specific command then the 'creator' is also marked 'as the checker' and command
     * automatically is processed and changes state of system.
     */
    public CommandSource handle(final CommandSource commandSource, final String apiRequestBodyInJson) {

        final AppUser maker = context.authenticatedUser();
        final LocalDate asToday = new LocalDate();

        CommandSource commandSourceResult = commandSource.copy();

        final Long resourceId = commandSource.resourceId();
        if (commandSource.isCreate()) {
            try {
                final UserCommand command = this.apiDataConversionService.convertApiRequestJsonToUserCommand(null, apiRequestBodyInJson);
                final String commandSerializedAsJson = this.commandSerializerService.serializeUserCommandToJson(command);
                commandSourceResult.updateJsonTo(commandSerializedAsJson);

                Long newResourceId = this.writePlatformService.createUser(command);
                commandSourceResult.markAsChecked(maker, asToday);
                commandSourceResult.updateResourceId(newResourceId);
            } catch (RollbackTransactionAsCommandIsNotApprovedByCheckerException e) {
                // swallow this rollback transaction by design
            }
        } else if (commandSource.isUpdate()) {
            try {
                final UserCommand command = this.apiDataConversionService.convertApiRequestJsonToUserCommand(resourceId, apiRequestBodyInJson);
                final String commandSerializedAsJson = this.commandSerializerService.serializeUserCommandToJson(command);
                commandSourceResult.updateJsonTo(commandSerializedAsJson);
                
                final String jsonOfChangesOnly = this.changeDetectionService.detectChangesOnUpdate(commandSource.resourceName(), commandSource.resourceId(), commandSerializedAsJson);
                commandSourceResult.updateJsonTo(jsonOfChangesOnly);

                final UserCommand changesOnly = this.commandDeserializerService.deserializeUserCommand(resourceId, jsonOfChangesOnly, false);

                if (maker.hasIdOf(command.getId())) {
                    this.writePlatformService.updateUsersOwnAccountDetails(changesOnly);
                } else {
                    this.writePlatformService.updateUser(changesOnly);
                }
                
                commandSourceResult.markAsChecked(maker, asToday);
            } catch (RollbackTransactionAsCommandIsNotApprovedByCheckerException e) {
                // swallow this rollback transaction by design
            }
        } else if (commandSource.isDelete()) {
            try {
                final UserCommand command = this.apiDataConversionService.convertApiRequestJsonToUserCommand(resourceId, apiRequestBodyInJson);
                final String commandSerializedAsJson = this.commandSerializerService.serializeUserCommandToJson(command);
                commandSourceResult.updateJsonTo(commandSerializedAsJson);
                
                this.writePlatformService.deleteUser(command);
                commandSourceResult.markAsChecked(maker, asToday);
            } catch (RollbackTransactionAsCommandIsNotApprovedByCheckerException e) {
                // swallow this rollback transaction by design
            }
        }

        return commandSourceResult;
    }

    /*
     * Used when users with 'checker' capability approve a command.
     */
    public CommandSource handle(final CommandSource commandSourceResult) {

        final AppUser checker = context.authenticatedUser();
        
        Long resourceId = commandSourceResult.resourceId();
        if (commandSourceResult.isUserResource()) {
            if (commandSourceResult.isCreate()) {
                final UserCommand command = this.commandDeserializerService.deserializeUserCommand(resourceId, commandSourceResult.json(), true);
                resourceId = this.writePlatformService.createUser(command);
                commandSourceResult.updateResourceId(resourceId);
                commandSourceResult.markAsChecked(checker, new LocalDate());
            } else if (commandSourceResult.isUpdate()) {
                final UserCommand command = this.commandDeserializerService.deserializeUserCommand(resourceId, commandSourceResult.json(), true);
                
                if (checker.hasIdOf(command.getId())) {
                    this.writePlatformService.updateUsersOwnAccountDetails(command);
                } else {
                    this.writePlatformService.updateUser(command);
                }
                
                commandSourceResult.markAsChecked(checker, new LocalDate());
            } else if (commandSourceResult.isDelete()) {
                final UserCommand command = this.commandDeserializerService.deserializeUserCommand(resourceId, commandSourceResult.json(), true);
                this.writePlatformService.deleteUser(command);
                commandSourceResult.markAsChecked(checker, new LocalDate());
            }
        }

        return commandSourceResult;
    }
}