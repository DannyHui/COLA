package com.alibaba.cola.event;

import com.alibaba.cola.dto.ErrorCodeI;
import com.alibaba.cola.dto.Response;
import com.alibaba.cola.exception.framework.BaseException;
import com.alibaba.cola.exception.framework.BasicErrorCode;
import com.alibaba.cola.exception.framework.ColaException;
import com.alibaba.cola.exception.framework.ExceptionHandlerFactory;
import com.alibaba.cola.logger.Logger;
import com.alibaba.cola.logger.LoggerFactory;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Event Bus
 *
 * @author shawnzhan.zxy
 * @date 2017/11/20
 *
 */
public class DefaultEventBus implements EventBusI{

    Logger logger = LoggerFactory.getLogger(DefaultEventBus.class);

    /**
     * 默认线程池
     *     如果处理器无定制线程池，则使用此默认
     */
    ExecutorService defaultExecutor = new ThreadPoolExecutor(Runtime.getRuntime().availableProcessors() + 1,
                                   Runtime.getRuntime().availableProcessors() + 1,
                                   0L,TimeUnit.MILLISECONDS,
                           new LinkedBlockingQueue<Runnable>(1000),new ThreadFactoryBuilder().setNameFormat("event-bus-pool-%d").build());

    @Autowired
    private EventHub eventHub;

    @SuppressWarnings("unchecked")
    @Override
    public Response fire(EventI event) {
        Response response = null;
        EventHandlerI eventHandler = null;
        try {
            eventHandler = eventHub.getEventHandler(event.getClass()).get(0);
            response = eventHandler.execute(event);
        }catch (Exception exception) {
            response = handleException(eventHandler, response, exception);
            ExceptionHandlerFactory.getExceptionHandler().handleException(event,response,exception);
            throw exception;
        }
        return response;
    }

    @Override
    public void fireAll(EventI event){
        eventHub.getEventHandler(event.getClass()).stream().map(p->{
            Response response = null;
            try {
                response = p.execute(event);
            }catch (Exception exception) {
                response = handleException(p, response, exception);
                ExceptionHandlerFactory.getExceptionHandler().handleException(event,response,exception);
            }
            return response;
        }).collect(Collectors.toList());
    }

    @Override
    public void asyncFire(EventI event){
        eventHub.getEventHandler(event.getClass()).parallelStream().map(p->{
            Response response = null;
            try {
                if(null != p.getExecutor()){
                    p.getExecutor().submit(()->p.execute(event));
                }else{
                    defaultExecutor.submit(()->p.execute(event));
                }
            }catch (Exception exception) {
                response = handleException(p, response, exception);
                ExceptionHandlerFactory.getExceptionHandler().handleException(event,response,exception);
            }
            return response;
        }).collect(Collectors.toList());
    }

    private Response handleException(EventHandlerI handler, Response response, Exception exception) {
        logger.error(exception.getMessage(), exception);
        Class responseClz = eventHub.getResponseRepository().get(handler.getClass());
        try {
            response = (Response) responseClz.newInstance();
        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new ColaException(e.getMessage());
        }
        if (exception instanceof BaseException) {
            ErrorCodeI errCode = ((BaseException) exception).getErrCode();
            response.setErrCode(errCode.getErrCode());
        }
        else {
            response.setErrCode(BasicErrorCode.SYS_ERROR.getErrCode());
        }
        response.setErrMessage(exception.getMessage());
        return response;
    }
}
