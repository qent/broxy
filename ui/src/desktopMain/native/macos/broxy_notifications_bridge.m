#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>
#import <dispatch/dispatch.h>
#import <jni.h>

static const jint BROXY_AUTH_STATUS_NOT_DETERMINED = 0;
static const jint BROXY_AUTH_STATUS_DENIED = 1;
static const jint BROXY_AUTH_STATUS_AUTHORIZED = 2;
static const jint BROXY_AUTH_STATUS_PROVISIONAL = 3;
static const jint BROXY_AUTH_STATUS_UNSUPPORTED_CONTEXT = 4;
static const jint BROXY_AUTH_STATUS_ERROR = 5;

static const jint BROXY_REQUEST_RESULT_STARTED = 0;
static const jint BROXY_REQUEST_RESULT_UNSUPPORTED_CONTEXT = 1;
static const jint BROXY_REQUEST_RESULT_ERROR = 2;

static const jint BROXY_POST_RESULT_POSTED = 0;
static const jint BROXY_POST_RESULT_UNSUPPORTED_CONTEXT = 1;
static const jint BROXY_POST_RESULT_NOT_AUTHORIZED = 2;
static const jint BROXY_POST_RESULT_INVALID_INPUT = 3;
static const jint BROXY_POST_RESULT_ERROR = 4;

static const NSTimeInterval BROXY_SETTINGS_TIMEOUT_SECONDS = 5.0;
static const NSTimeInterval BROXY_ADD_REQUEST_TIMEOUT_SECONDS = 5.0;

@interface BroxyNotificationCenterDelegate : NSObject <UNUserNotificationCenterDelegate>
@end

@implementation BroxyNotificationCenterDelegate

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions options))completionHandler {
    (void)center;
    (void)notification;

    if (completionHandler == nil) {
        return;
    }

    if (@available(macOS 11.0, *)) {
        completionHandler(UNNotificationPresentationOptionList |
                          UNNotificationPresentationOptionBanner |
                          UNNotificationPresentationOptionSound);
    } else {
        completionHandler(UNNotificationPresentationOptionAlert |
                          UNNotificationPresentationOptionSound);
    }
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
didReceiveNotificationResponse:(UNNotificationResponse *)response
         withCompletionHandler:(void (^)(void))completionHandler {
    (void)center;
    (void)response;

    if (completionHandler != nil) {
        completionHandler();
    }
}

@end

static BOOL broxy_is_app_bundle_context(void) {
    NSBundle *mainBundle = [NSBundle mainBundle];
    if (mainBundle == nil) {
        return NO;
    }

    NSString *bundlePath = [mainBundle bundlePath];
    if (bundlePath == nil || bundlePath.length == 0) {
        return NO;
    }

    NSString *lowercasePath = [bundlePath lowercaseString];
    NSString *pathExtension = [[bundlePath pathExtension] lowercaseString];
    return [pathExtension isEqualToString:@"app"] || [lowercasePath containsString:@".app/"];
}

static BOOL broxy_is_framework_available(void) {
    if (@available(macOS 10.14, *)) {
        return NSClassFromString(@"UNUserNotificationCenter") != nil;
    }
    return NO;
}

static BOOL broxy_is_supported_context(void) {
    return broxy_is_framework_available() && broxy_is_app_bundle_context();
}

static BroxyNotificationCenterDelegate *broxy_delegate_instance(void) {
    static BroxyNotificationCenterDelegate *delegate = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{
        delegate = [BroxyNotificationCenterDelegate new];
    });
    return delegate;
}

static BOOL broxy_ensure_delegate(UNUserNotificationCenter *center) {
    if (center == nil) {
        return NO;
    }

    BroxyNotificationCenterDelegate *delegate = broxy_delegate_instance();
    if (delegate == nil) {
        return NO;
    }

    if (center.delegate != delegate) {
        center.delegate = delegate;
    }
    return YES;
}

static NSString *broxy_nsstring_from_jstring(JNIEnv *env, jstring value) {
    if (value == NULL) {
        return nil;
    }

    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (chars == NULL) {
        return nil;
    }

    jsize length = (*env)->GetStringLength(env, value);
    NSString *result = [[NSString alloc] initWithCharacters:(const unichar *)chars length:(NSUInteger)length];
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
}

static jint broxy_map_authorization_status(UNAuthorizationStatus status) {
    switch (status) {
        case UNAuthorizationStatusNotDetermined:
            return BROXY_AUTH_STATUS_NOT_DETERMINED;
        case UNAuthorizationStatusDenied:
            return BROXY_AUTH_STATUS_DENIED;
        case UNAuthorizationStatusAuthorized:
            return BROXY_AUTH_STATUS_AUTHORIZED;
        case UNAuthorizationStatusProvisional:
            return BROXY_AUTH_STATUS_PROVISIONAL;
        default:
            return BROXY_AUTH_STATUS_ERROR;
    }
}

static jint broxy_fetch_authorization_status(UNUserNotificationCenter *center) {
    if (center == nil) {
        return BROXY_AUTH_STATUS_ERROR;
    }

    __block UNAuthorizationStatus authStatus = UNAuthorizationStatusNotDetermined;
    __block BOOL completed = NO;
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);

    [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings * _Nonnull settings) {
        if (settings != nil) {
            authStatus = settings.authorizationStatus;
        }
        completed = YES;
        dispatch_semaphore_signal(semaphore);
    }];

    dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(BROXY_SETTINGS_TIMEOUT_SECONDS * NSEC_PER_SEC));
    long waitResult = dispatch_semaphore_wait(semaphore, timeout);
    if (waitResult != 0 || !completed) {
        return BROXY_AUTH_STATUS_ERROR;
    }

    return broxy_map_authorization_status(authStatus);
}

JNIEXPORT jboolean JNICALL
Java_io_qent_broxy_ui_MacOsNotificationNativeBridge_nativeIsSupportedContext(JNIEnv *env, jobject self) {
    (void)env;
    (void)self;

    @try {
        return broxy_is_supported_context() ? JNI_TRUE : JNI_FALSE;
    } @catch (NSException *exception) {
        (void)exception;
        return JNI_FALSE;
    }
}

JNIEXPORT jint JNICALL
Java_io_qent_broxy_ui_MacOsNotificationNativeBridge_nativeGetAuthorizationStatus(JNIEnv *env, jobject self) {
    (void)env;
    (void)self;

    @try {
        if (!broxy_is_supported_context()) {
            return BROXY_AUTH_STATUS_UNSUPPORTED_CONTEXT;
        }

        if (@available(macOS 10.14, *)) {
            UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
            if (!broxy_ensure_delegate(center)) {
                return BROXY_AUTH_STATUS_ERROR;
            }
            return broxy_fetch_authorization_status(center);
        }

        return BROXY_AUTH_STATUS_UNSUPPORTED_CONTEXT;
    } @catch (NSException *exception) {
        (void)exception;
        return BROXY_AUTH_STATUS_ERROR;
    }
}

JNIEXPORT jint JNICALL
Java_io_qent_broxy_ui_MacOsNotificationNativeBridge_nativeRequestAuthorization(JNIEnv *env, jobject self, jlong optionsMask) {
    (void)env;
    (void)self;

    @try {
        if (!broxy_is_supported_context()) {
            return BROXY_REQUEST_RESULT_UNSUPPORTED_CONTEXT;
        }

        if (@available(macOS 10.14, *)) {
            UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
            if (!broxy_ensure_delegate(center)) {
                return BROXY_REQUEST_RESULT_ERROR;
            }

            UNAuthorizationOptions options = (UNAuthorizationOptions)optionsMask;
            if (options == 0) {
                options = UNAuthorizationOptionAlert | UNAuthorizationOptionSound;
            }

            [center requestAuthorizationWithOptions:options
                                  completionHandler:^(BOOL granted, NSError * _Nullable error) {
                                      (void)granted;
                                      (void)error;
                                  }];
            return BROXY_REQUEST_RESULT_STARTED;
        }

        return BROXY_REQUEST_RESULT_UNSUPPORTED_CONTEXT;
    } @catch (NSException *exception) {
        (void)exception;
        return BROXY_REQUEST_RESULT_ERROR;
    }
}

JNIEXPORT jint JNICALL
Java_io_qent_broxy_ui_MacOsNotificationNativeBridge_nativePostNotification(JNIEnv *env, jobject self, jstring agentId, jstring title, jstring body) {
    (void)self;

    @try {
        if (!broxy_is_supported_context()) {
            return BROXY_POST_RESULT_UNSUPPORTED_CONTEXT;
        }

        if (@available(macOS 10.14, *)) {
            UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
            if (!broxy_ensure_delegate(center)) {
                return BROXY_POST_RESULT_ERROR;
            }

            jint authorizationStatus = broxy_fetch_authorization_status(center);
            if (authorizationStatus != BROXY_AUTH_STATUS_AUTHORIZED && authorizationStatus != BROXY_AUTH_STATUS_PROVISIONAL) {
                return BROXY_POST_RESULT_NOT_AUTHORIZED;
            }

            NSString *titleValue = broxy_nsstring_from_jstring(env, title);
            NSString *bodyValue = broxy_nsstring_from_jstring(env, body);
            NSString *agentValue = broxy_nsstring_from_jstring(env, agentId);
            if (titleValue == nil || bodyValue == nil) {
                return BROXY_POST_RESULT_INVALID_INPUT;
            }
            if (agentValue == nil) {
                agentValue = @"";
            }

            UNMutableNotificationContent *content = [[UNMutableNotificationContent alloc] init];
            content.title = titleValue;
            content.body = bodyValue;
            content.sound = [UNNotificationSound defaultSound];
            content.userInfo = @{ @"broxyAgentId": agentValue };

            NSString *requestIdentifier = [NSString stringWithFormat:@"broxy.agent.%@", [NSUUID UUID].UUIDString];
            UNNotificationRequest *request =
                [UNNotificationRequest requestWithIdentifier:requestIdentifier content:content trigger:nil];

            __block NSError *addError = nil;
            __block BOOL completed = NO;
            dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);

            [center addNotificationRequest:request
                     withCompletionHandler:^(NSError * _Nullable error) {
                         addError = error;
                         completed = YES;
                         dispatch_semaphore_signal(semaphore);
                     }];

            dispatch_time_t timeout = dispatch_time(DISPATCH_TIME_NOW, (int64_t)(BROXY_ADD_REQUEST_TIMEOUT_SECONDS * NSEC_PER_SEC));
            long waitResult = dispatch_semaphore_wait(semaphore, timeout);
            if (waitResult != 0 || !completed) {
                return BROXY_POST_RESULT_ERROR;
            }
            if (addError != nil) {
                return BROXY_POST_RESULT_ERROR;
            }
            return BROXY_POST_RESULT_POSTED;
        }

        return BROXY_POST_RESULT_UNSUPPORTED_CONTEXT;
    } @catch (NSException *exception) {
        (void)exception;
        return BROXY_POST_RESULT_ERROR;
    }
}
