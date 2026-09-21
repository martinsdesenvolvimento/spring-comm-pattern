package com.mds.comm.exception;

import com.mds.error.handler.exception.base.BaseException;

/**
 * Unchecked exception thrown when a Feign request interception fails.
 *
 * @author MDS
 * @since 0.0.1-SNAPSHOT
 */
public class PatternRequestException extends BaseException {

  public PatternRequestException(Throwable tx) {
    super(tx);
  }
}
