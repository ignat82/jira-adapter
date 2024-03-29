package ru.homecredit.jiraadapter.webwork;

import com.atlassian.jira.web.action.JiraWebActionSupport;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import ru.homecredit.jiraadapter.entities.FieldsGroupSettings;
import ru.homecredit.jiraadapter.service.SettingsServiceImpl;

import java.util.List;

@Getter
@Setter
@RequiredArgsConstructor
@Slf4j
public class FieldsSettingsWebwork extends JiraWebActionSupport {
    private final SettingsServiceImpl settingsService;
    private List<String> allCustomFieldsKeys;
    private List<String> savedCustomFieldsKeys;
    private String[] customFieldsKeysToSave;
    private List<String> allUsers;
    private List<String> savedUsers;
    private String[] usersToSave;
    private List<FieldsGroupSettings> currentSettings;
    private String groupID;
    private String description;
    private String message ="";

    @Override
    public String execute() throws Exception {
        super.execute();
        allCustomFieldsKeys = settingsService.getAllCustomFieldsKeys();
        allUsers = settingsService.getAllUsers();
        currentSettings = settingsService.all();
        log.info("get settings objects: {}", currentSettings);
        currentSettings.forEach(s -> log.info(settingsService.prettyString(s)));
        return "fields-groups-settings-page";
    }
    public void doSave() {
        log.info("saving new settings group. fields {}, users{}",
                 customFieldsKeysToSave,
                 usersToSave);
        message = settingsService
                .add(description, customFieldsKeysToSave, usersToSave);
    }

    public void doDelete() {
        log.info("deleting settings group {}", groupID);
        message = settingsService.delete(Integer.parseInt(groupID));
    }

    public void doEdit() {
        log.info("editing settings group {}", groupID);
        message = settingsService.edit(Integer.parseInt(groupID),
                                       description,
                                       customFieldsKeysToSave,
                                       usersToSave);
    }
}
