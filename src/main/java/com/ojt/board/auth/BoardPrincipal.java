package com.ojt.board.auth;

import java.io.Serializable;

/** Local account identity shared by password and external-provider sessions. */
public interface BoardPrincipal extends Serializable {
    Long getId();
    String getEmail();
    String getNickname();
}
