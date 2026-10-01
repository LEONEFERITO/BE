package com.leoneferito.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

@Component
public class SessionTerminator {
    private static final Logger log = LoggerFactory.getLogger(SessionTerminator.class);
    private final FindByIndexNameSessionRepository<?> sessions;
    public SessionTerminator(FindByIndexNameSessionRepository<?> sessions){
        this.sessions = sessions;
    }
    public int terminateAll(String email){
        return terminate(email, null);
    }
    public int terminateOthers(String email, String keepSessionId){
        return terminate(email, keepSessionId);
    }
    private int terminate(String email, String keepSessionId){
        int count = 0;
        for (String id : sessions.findByPrincipalName(email).keySet()){
            if(!id.equals(keepSessionId)){
                sessions.deleteById(id);
                count++;
            }
        }
        log.info("세션 종료 count={}", count);
        return count;
    }
}
