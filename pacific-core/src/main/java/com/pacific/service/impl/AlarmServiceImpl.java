package com.pacific.service.impl;

import com.pacific.common.exception.PacificException;
import com.pacific.common.json.FastJson;
import com.pacific.common.utils.CollectionUtil;
import com.pacific.common.utils.NamedThreadFactory;
import com.pacific.common.utils.VelocityTemplateUtil;
import com.pacific.domain.dto.ApplicationUserConfigDto;
import com.pacific.domain.dto.ChannelDto;
import com.pacific.domain.entity.AlarmLog;
import com.pacific.domain.entity.AlarmTemplate;
import com.pacific.domain.entity.ApplicationUserConfig;
import com.pacific.domain.entity.ErrorLogRecord;
import com.pacific.domain.enums.ChannelCodeEnums;
import com.pacific.mapper.AlarmLogMapper;
import com.pacific.mapper.AlarmTemplateMapper;
import com.pacific.mapper.ApplicationUserConfigMapper;
import com.pacific.mapper.ErrorLogRecordMapper;
import com.pacific.service.AlarmService;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.context.Context;
import org.apache.velocity.context.EvaluateContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;

/**
 * Created by Fe on 16/5/27.
 */
public class AlarmServiceImpl implements AlarmService {

    public static final Logger logger = LoggerFactory.getLogger(AlarmServiceImpl.class);

    public static ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1,new NamedThreadFactory("alarm-error-log"));

    @Resource
    private ErrorLogRecordMapper errorLogRecordMapper;

    @Resource
    private ApplicationUserConfigMapper applicationUserConfigMapper;

    @Resource
    private AlarmLogMapper alarmLogMapper;

    @Resource
    private AlarmTemplateMapper alarmTemplateMapper;


    @PostConstruct
    public void init() {
        scheduledExecutorService.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                alarm();
            }
        },0,2, TimeUnit.SECONDS);
    }


    @Override
    public void alarm() {
        List<ErrorLogRecord> errorLogRecordList = errorLogRecordMapper.queryHasNoAlarmErrorLogRecord();
        logger.info("will be alarm error log data : {}", FastJson.toJson(errorLogRecordList));
        if (CollectionUtil.isNotEmpty(errorLogRecordList)) {
            Map<String,List<ApplicationUserConfigDto>> appMap = groupByApplicationCode();

            if (appMap != null && appMap.size() > 0) {
                for (ErrorLogRecord errorLogRecord : errorLogRecordList) {
                    String applicationCode = errorLogRecord.getApplicationCode();

                    List<ApplicationUserConfigDto> applicationUserConfigDtoList = appMap.get(applicationCode);
                    if (CollectionUtil.isNotEmpty(applicationUserConfigDtoList)) {
                        //TODO 根据每个用户的报警规则 开启报警
                        for (ApplicationUserConfigDto applicationUserConfigDto : applicationUserConfigDtoList) {
                            processApplicationUserConfig(applicationUserConfigDto,errorLogRecord);
                        }
                    }
                }
            }
        }
    }

    private void processApplicationUserConfig(ApplicationUserConfigDto applicationUserConfigDto,ErrorLogRecord errorLogRecord) {
        String isMonitorAllErrorLog = applicationUserConfigDto.getIsMonitorAllErrorLog();
        String channelConfig = applicationUserConfigDto.getChannelConfig();
        String keyWords = applicationUserConfigDto.getMonitorErrorLogKeywords();
        List<ChannelDto> channelDtoList = buildChannelList(channelConfig);
        if (channelConfig == null || channelDtoList.size() == 0) PacificException.throwEx("applicationUserConfig appCode : {" + applicationUserConfigDto.getApplicationCode() + "} , userId : {" + applicationUserConfigDto.getUserId() + "}, has no config channelConfig,");

        for (ChannelDto channelDto : channelDtoList) {
            if (isMonitorAllErrorLog.equals("y")) {
                saveAlarmLog(channelDto,applicationUserConfigDto,errorLogRecord);
            } else {
                String logMessage = errorLogRecord.getLogMessage();
                List<String> keyWordsList = FastJson.jsonToList(keyWords,String.class);
                boolean flag = checkKeyWordsIsExists(keyWordsList,logMessage);
                if (flag) {
                    saveAlarmLog(channelDto,applicationUserConfigDto,errorLogRecord);
                }
            }
        }

        ErrorLogRecord updateErrorLogRecordParam = new ErrorLogRecord();
        updateErrorLogRecordParam.setId(errorLogRecord.getId());
        updateErrorLogRecordParam.setIsNotify("y");
        errorLogRecordMapper.updateByPrimaryKeySelective(updateErrorLogRecordParam);
    }

    private void saveAlarmLog(ChannelDto channelDto,ApplicationUserConfigDto applicationUserConfigDto,
                              ErrorLogRecord errorLogRecord) {

        AlarmTemplate alarmTemplate = alarmTemplateMapper.selectByChannelCode(channelDto.getChannelCode());

        if (alarmTemplate == null) PacificException.throwEx("alarmTemplate has not init!");

        AlarmLog alarmLog = new AlarmLog();
        alarmLog.setCreateTime(new Date());
        alarmLog.setUserId(applicationUserConfigDto.getUserId());
        alarmLog.setApplicationCode(applicationUserConfigDto.getApplicationCode());
        alarmLog.setChannelCode(channelDto.getChannelCode());

        alarmLog.setMessage(getMessage(alarmTemplate,applicationUserConfigDto,errorLogRecord));
        alarmLog.setSendTime(new Date());
        alarmLog.setUpdateTime(new Date());
        alarmLog.setErrorLogId(errorLogRecord.getId());
        alarmLogMapper.insertSelective(alarmLog);

        alarmToAppUser(channelDto);
    }
    private String getMessage(AlarmTemplate alarmTemplate,ApplicationUserConfigDto applicationUserConfigDto,ErrorLogRecord errorLogRecord) {
        String templateText = alarmTemplate.getTemplateText();
        Context context = new VelocityContext();
        return VelocityTemplateUtil.merge(templateText,context);
    }



    private boolean checkKeyWordsIsExists(List<String> keyWordsList,String logMessage) {
        boolean flag = false;
        if (CollectionUtil.isNotEmpty(keyWordsList))  {
            for (String str : keyWordsList) {
                if (logMessage.contains(str))  {
                    flag = true;
                    break;
                }
            }
        }
        return flag;

    }

    private void alarmToAppUser(ChannelDto channelDto) {
        if (channelDto.isOpen()) {
            if (channelDto.getChannelCode().equals(ChannelCodeEnums.PHONE_MESSAGE.getCode())) {

            }

            if (channelDto.getChannelCode().equals(ChannelCodeEnums.EMAIL.getCode())) {

            }

            if (channelDto.getChannelCode().equals(ChannelCodeEnums.BEARY_CHAT)) {

            }
        }
    }

    private List<ChannelDto> buildChannelList(String channelConfigJson) {
        return FastJson.jsonToList(channelConfigJson,ChannelDto.class);
    }

    private Map<String,List<ApplicationUserConfigDto>> groupByApplicationCode() {
        List<ApplicationUserConfigDto> applicationUserConfigList = applicationUserConfigMapper.queryAllApplicationUserConfigByCode();
        Map<String,List<ApplicationUserConfigDto>> userConfigMap = new HashMap<String,List<ApplicationUserConfigDto>>();

        if (CollectionUtil.isNotEmpty(applicationUserConfigList)) {
            for (ApplicationUserConfigDto applicationUserConfigDto : applicationUserConfigList) {
                String applicationCode = applicationUserConfigDto.getApplicationCode();

                List<ApplicationUserConfigDto> appList = null;
                if (userConfigMap.containsKey(applicationCode)) {
                    appList = userConfigMap.get(applicationCode);
                } else {
                    appList = new ArrayList<ApplicationUserConfigDto>();
                    userConfigMap.put(applicationCode,appList);
                }
                appList.add(applicationUserConfigDto);
            }
        }
        return userConfigMap;
    }

    public static void main(String args[]) {
    }
}
