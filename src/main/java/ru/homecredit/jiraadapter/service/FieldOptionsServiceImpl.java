package ru.homecredit.jiraadapter.service;

import com.atlassian.jira.issue.context.IssueContext;
import com.atlassian.jira.issue.context.IssueContextImpl;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.fields.ConfigurableField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.homecredit.jiraadapter.dto.Constants;
import ru.homecredit.jiraadapter.dto.FieldOptions;
import ru.homecredit.jiraadapter.dto.FieldParameters;
import ru.homecredit.jiraadapter.dto.JiraAdapterSettings;
import ru.homecredit.jiraadapter.dto.request.FieldOptionsRequest;

import java.util.*;

import static ru.homecredit.jiraadapter.dto.request.FieldOptionsRequest.Action.DISABLE;

/**
 * service class for manging customfield options trough Jira Java API by handling the
 * data, received from controller in form of request body string, and returning to controller the
 * FieldOption transport object with information of the action preformed and some Jira parameters
 */
@Slf4j
@RequiredArgsConstructor
public class FieldOptionsServiceImpl implements FieldOptionsService {
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final FieldManager fieldManager;
    private final ProjectManager projectManager;
    private final OptionsManager optionsManager;
    private final JiraAdapterSettingsService jiraAdapterSettingsService;

    public FieldOptions getOptions(String fieldKey,
                                    String projectKey,
                                    String issueTypeId) {
        FieldOptions fieldOptions
                = initializeFieldOptions(new FieldOptionsRequest(fieldKey, projectKey, issueTypeId));
        if (fieldOptions.getErrorMessage() == null) {
            fieldOptions.setSuccess(true);
        }
        return fieldOptions;
    }

    /**
     * method that does the manipulation on field and option, received in POST
     * request body, and packs the success and some Jira properties to
     * FieldOptions DTO
     * @param requestBody - string, received in request body by controller
     * @return FieldOptions DTO
     */
    public FieldOptions postOption(String requestBody) {
        FieldOptionsRequest fieldOptionsRequest = extractRequestParameters(requestBody);
        if (fieldOptionsRequest == null) {
            FieldOptions fieldOptions = new FieldOptions();
            fieldOptions.setErrorMessage("failed to parse request parameters");
            return fieldOptions;
        }
        FieldOptions fieldOptions = initializeFieldOptions(fieldOptionsRequest);
        log.info("context is valid - {}", fieldOptions.getFieldParameters().isValidContext());
        if (fieldOptions.getErrorMessage() != null) {
            log.error("shutting down postOption cuz fieldOptions object wasn't constructed");
            return fieldOptions;
        }
        if (!fieldOptions.getFieldParameters().isValidContext()) {
            fieldOptions.setErrorMessage("failed to initialize field parameters. invalid context?" +
                                                 " field and project keys?");
            return fieldOptions;
        }
        if (!fieldOptions.getFieldParameters().isPermittedToEdit()) {
            fieldOptions.setErrorMessage("field is not permitted for edit by plugin settings");
            return fieldOptions;
        }
        switch (fieldOptions.getFieldOptionsRequest().getAction()) {
            case NOT_RECOGNIZED: {
                fieldOptions.setErrorMessage("action parameter not recognized");
                return fieldOptions;
            }
            case ADD: {
                String newOptionValue = fieldOptions.getFieldOptionsRequest().getNewOption();
                if (newOptionValue.equals(Constants.DEFAULT_RECEIVED)) {
                    fieldOptions.setErrorMessage("newOption not provided");
                    return fieldOptions;
                }
                log.trace("trying to add new option \"{}\"", newOptionValue);
                if (Arrays.asList(fieldOptions.getFieldOptionsArr()).contains(newOptionValue)) {
                    fieldOptions.setErrorMessage(
                            "new option " + newOptionValue + " already exists");
                    return fieldOptions;
                }
                int size = fieldOptions.getFieldOptionsArr().length;
                optionsManager.createOption(fieldOptions.getFieldParameters().getFieldConfig(),
                                            null,
                                            (long) (size + 1),
                                            newOptionValue);
                fieldOptions.setSuccess(true);
                log.trace("added option \"{}\" to Options", newOptionValue);
                /* acquiring Options object and Options from it once again, cuz the
                new one was appended */
                initializeOptions(fieldOptions);
                return fieldOptions;
            }
            case DISABLE: // will be set at ENABLE block if necessary
            case ENABLE: {
                String optionValue = fieldOptions.getFieldOptionsRequest().getNewOption();
                Options options = optionsManager.getOptions(
                        fieldOptions.getFieldParameters().getFieldConfig()
                );
                if (options.getOptionForValue(optionValue, null) != null) {
                    options.getOptionForValue(optionValue, null).setDisabled(
                            fieldOptions.getFieldOptionsRequest().getAction() == DISABLE
                    );
                    fieldOptions.setSuccess(true);
                    log.trace("enabled option \"{}\"", optionValue);
                } else {
                    log.error("option \"{}\" seems not to exist. shutting down", optionValue);
                }
                return fieldOptions;
            }
        }
        return fieldOptions;
    }

    /**
     * method to acquire the options of given field in given context
     * @param fieldOptionsRequest - DTO with request parameters
     * @return - FieldOptions transport object
     */
    private FieldOptions initializeFieldOptions(FieldOptionsRequest fieldOptionsRequest) {
        FieldOptions fieldOptions = new FieldOptions();
        fieldOptions.setFieldOptionsRequest(fieldOptionsRequest);
        FieldParameters fieldParameters = initializeFieldParameters(fieldOptionsRequest);
        if (fieldParameters == null) {
            fieldOptions.setFieldParameters(new FieldParameters());
            return fieldOptions;
        }
        fieldOptions.setFieldParameters(fieldParameters);
        initializeOptions(fieldOptions);
        return fieldOptions;
    }

    /**
     * helper method to parse the request body and extract request parameters from it
     * @param requestBody - String, received by controller from POST request
     * @return - FieldOptionsRequest DTO
     */
    private FieldOptionsRequest extractRequestParameters(String requestBody) {
        FieldOptionsRequest fieldOptionsRequest = null;
        try {
            fieldOptionsRequest = gson.fromJson(requestBody, FieldOptionsRequest.class);
            if (fieldOptionsRequest.getAction() == null) {
                log.warn("got null action. setting default");
                fieldOptionsRequest.setAction(FieldOptionsRequest.Action.NOT_RECOGNIZED);
            }
            log.info("json deserialized \n{}", fieldOptionsRequest);
        } catch (Exception e) {
            log.error("could not parse fieldOptionsRequest body - {}", requestBody);
            log.error("exception is - {}", e.getMessage());
        }
        return fieldOptionsRequest;
    }

    /**
     *
     * @param fieldOptionsRequest - DTO, containing request parameters
     * @return - FieldParameters DTO with some Jira properties of manipulated customfield
     */
    private FieldParameters initializeFieldParameters(FieldOptionsRequest fieldOptionsRequest) {
        FieldParameters fieldParameters = new FieldParameters();
        try {
            ConfigurableField field = fieldManager.
                 getConfigurableField(fieldOptionsRequest.getFieldKey());
            fieldParameters.setFieldName(field.getName());
            Project project = projectManager.
                  getProjectByCurrentKeyIgnoreCase(fieldOptionsRequest.getProjectKey());
            fieldParameters.setProjectName(project.getName());
            IssueContext issueContext =
                    new IssueContextImpl(project.getId(), fieldOptionsRequest.getIssueTypeId());
            log.trace("issue context is " + issueContext);
            FieldConfig fieldConfig = field.getRelevantConfig(issueContext);
            log.trace("field config is - " + fieldConfig);
            fieldParameters.setFieldConfig(fieldConfig);
            fieldParameters.setFieldConfigName(fieldConfig.getName());
            fieldParameters.setValidContext(true);
            log.trace("valid context " + fieldParameters.isValidContext());
            JiraAdapterSettings jiraAdapterSettings = jiraAdapterSettingsService.getSettings();
            List<String> editableFields = jiraAdapterSettings.getEditableFields();
            log.trace("editable fields are - {}", editableFields);
            boolean permittedToEdit = jiraAdapterSettingsService.getSettings().getEditableFields().
                    contains(fieldOptionsRequest.getFieldKey());
            fieldParameters.setPermittedToEdit(permittedToEdit);
        } catch (Exception e) {
            log.error("failed to initialize field parameters with error {}", e.toString());
            return null;
        }
        return fieldParameters;
    }

    /**
     * method to initialize options of field, attributes of which are stored in
     * manipulated FieldOptions DTO
     * @param fieldOptions - transport object
     */
    private void initializeOptions(FieldOptions fieldOptions) {
        Options options = Objects.requireNonNull(optionsManager.
              getOptions(fieldOptions.getFieldParameters().getFieldConfig()),
              "failed to acquire Options object");
        fieldOptions.setFieldOptionsArr(options.stream().map(op -> op.getValue()).toArray(String[]::new));
        Map<String, Boolean> isDisabled = new HashMap<>();
        for (Option option : options) {
            isDisabled.put(option.getValue(), option.getDisabled());
        }
        fieldOptions.setIsDisabled(isDisabled);
        log.trace("field options are {}", fieldOptions.getFieldOptionsArr());
    }

}