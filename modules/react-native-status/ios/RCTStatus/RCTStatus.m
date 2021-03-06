#import "RCTStatus.h"
#import "ReactNativeConfig.h"
#import "React/RCTBridge.h"
#import "React/RCTEventDispatcher.h"
#import <Statusgo/Statusgo.h>

@interface NSDictionary (BVJSONString)
-(NSString*) bv_jsonStringWithPrettyPrint:(BOOL) prettyPrint;
@end

@implementation NSDictionary (BVJSONString)

-(NSString*) bv_jsonStringWithPrettyPrint:(BOOL) prettyPrint {
    NSError *error;
    NSData *jsonData = [NSJSONSerialization dataWithJSONObject:self
                                                       options:(NSJSONWritingOptions)    (prettyPrint ? NSJSONWritingPrettyPrinted : 0)
                                                         error:&error];

    if (! jsonData) {
        NSLog(@"bv_jsonStringWithPrettyPrint: error: %@", error.localizedDescription);
        return @"{}";
    } else {
        return [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
    }
}
@end

@interface NSArray (BVJSONString)
- (NSString *)bv_jsonStringWithPrettyPrint:(BOOL)prettyPrint;
@end

@implementation NSArray (BVJSONString)
-(NSString*) bv_jsonStringWithPrettyPrint:(BOOL) prettyPrint {
    NSError *error;
    NSData *jsonData = [NSJSONSerialization dataWithJSONObject:self
                                                       options:(NSJSONWritingOptions) (prettyPrint ? NSJSONWritingPrettyPrinted : 0)
                                                         error:&error];

    if (! jsonData) {
        NSLog(@"bv_jsonStringWithPrettyPrint: error: %@", error.localizedDescription);
        return @"[]";
    } else {
        return [[NSString alloc] initWithData:jsonData encoding:NSUTF8StringEncoding];
    }
}
@end

static bool isStatusInitialized;
static RCTBridge *bridge;
@implementation Status{
}

-(RCTBridge *)bridge
{
    return bridge;
}

-(void)setBridge:(RCTBridge *)newBridge
{
    bridge = newBridge;
}

RCT_EXPORT_MODULE();

////////////////////////////////////////////////////////////////////
#pragma mark - startNode
//////////////////////////////////////////////////////////////////// startNode
RCT_EXPORT_METHOD(startNode:(NSString *)configString) {
#if DEBUG
    NSLog(@"StartNode() method called");
#endif
    NSFileManager *fileManager = [NSFileManager defaultManager];
    NSError *error = nil;
    NSURL *rootUrl =[[fileManager
                      URLsForDirectory:NSDocumentDirectory inDomains:NSUserDomainMask]
                     lastObject];
    NSURL *absTestnetFolderName = [rootUrl URLByAppendingPathComponent:@"ethereum/testnet"];

    if (![fileManager fileExistsAtPath:absTestnetFolderName.path])
        [fileManager createDirectoryAtPath:absTestnetFolderName.path withIntermediateDirectories:YES attributes:nil error:&error];

    NSURL *flagFolderUrl = [rootUrl URLByAppendingPathComponent:@"ropsten_flag"];

    if(![fileManager fileExistsAtPath:flagFolderUrl.path]){
        NSLog(@"remove lightchaindata");
        NSURL *absLightChainDataUrl = [absTestnetFolderName URLByAppendingPathComponent:@"StatusIM/lightchaindata"];
        if([fileManager fileExistsAtPath:absLightChainDataUrl.path]) {
            [fileManager removeItemAtPath:absLightChainDataUrl.path
                                    error:nil];
        }
        [fileManager createDirectoryAtPath:flagFolderUrl.path
               withIntermediateDirectories:NO
                                attributes:nil
                                     error:&error];
    }

    NSLog(@"after remove lightchaindata");

    NSURL *absTestnetKeystoreUrl = [absTestnetFolderName URLByAppendingPathComponent:@"keystore"];
    NSURL *absKeystoreUrl = [rootUrl URLByAppendingPathComponent:@"keystore"];
    if([fileManager fileExistsAtPath:absTestnetKeystoreUrl.path]){
        NSLog(@"copy keystore");
        [fileManager copyItemAtPath:absTestnetKeystoreUrl.path toPath:absKeystoreUrl.path error:nil];
        [fileManager removeItemAtPath:absTestnetKeystoreUrl.path error:nil];
    }

    NSLog(@"after lightChainData");

    NSLog(@"preconfig: %@", configString);
    NSData *configData = [configString dataUsingEncoding:NSUTF8StringEncoding];
    NSDictionary *configJSON = [NSJSONSerialization JSONObjectWithData:configData options:NSJSONReadingMutableContainers error:nil];
    NSString *relativeDataDir = [configJSON objectForKey:@"DataDir"];
    NSString *absDataDir = [rootUrl.path stringByAppendingString:relativeDataDir];
    NSURL *absDataDirUrl = [NSURL fileURLWithPath:absDataDir];
    NSURL *absLogUrl = [absDataDirUrl URLByAppendingPathComponent:@"geth.log"];
    [configJSON setValue:absDataDirUrl.path forKey:@"DataDir"];
    [configJSON setValue:absKeystoreUrl.path forKey:@"KeyStoreDir"];
    [configJSON setValue:absLogUrl.path forKey:@"LogFile"];

    NSString *resultingConfig = [configJSON bv_jsonStringWithPrettyPrint:NO];
    NSLog(@"node config %@", resultingConfig);

    if(![fileManager fileExistsAtPath:absDataDirUrl.path]) {
        [fileManager createDirectoryAtPath:absDataDirUrl.path withIntermediateDirectories:YES attributes:nil error:nil];
    }

    NSLog(@"logUrlPath %@", absLogUrl.path);
    if(![fileManager fileExistsAtPath:absLogUrl.path]) {
        NSMutableDictionary *dict = [[NSMutableDictionary alloc] init];
        [dict setObject:[NSNumber numberWithInt:511] forKey:NSFilePosixPermissions];
        [fileManager createFileAtPath:absLogUrl.path contents:nil attributes:dict];
    }

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0),
                   ^(void)
                   {
                       char *res = StartNode((char *) [resultingConfig UTF8String]);
                       NSLog(@"StartNode result %@", [NSString stringWithUTF8String: res]);
                   });
}

////////////////////////////////////////////////////////////////////
#pragma mark - shouldMoveToInternalStorage
//////////////////////////////////////////////////////////////////// shouldMoveToInternalStorage
RCT_EXPORT_METHOD(shouldMoveToInternalStorage:(RCTResponseSenderBlock)onResultCallback) {
    // Android only
    onResultCallback(@[[NSNull null]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - moveToInternalStorage
//////////////////////////////////////////////////////////////////// moveToInternalStorage
RCT_EXPORT_METHOD(moveToInternalStorage:(RCTResponseSenderBlock)onResultCallback) {
    // Android only
    onResultCallback(@[[NSNull null]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - StopNode method
//////////////////////////////////////////////////////////////////// StopNode
RCT_EXPORT_METHOD(stopNode) {
#if DEBUG
    NSLog(@"StopNode() method called");
#endif
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0),
                   ^(void)
                   {
                       char *res = StopNode();
                       NSLog(@"StopNode result %@", [NSString stringWithUTF8String: res]);
                   });
}

////////////////////////////////////////////////////////////////////
#pragma mark - Accounts method
//////////////////////////////////////////////////////////////////// createAccount
RCT_EXPORT_METHOD(createAccount:(NSString *)password
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"CreateAccount() method called");
#endif
    char * result = CreateAccount((char *) [password UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - NotifyUsers method
//////////////////////////////////////////////////////////////////// notifyUsers
RCT_EXPORT_METHOD(notifyUsers:(NSString *)message
                  payloadJSON:(NSString *)payloadJSON
                  tokensJSON:(NSString *)tokensJSON
                  callback:(RCTResponseSenderBlock)callback) {
    char * result = NotifyUsers((char *) [message UTF8String], (char *) [payloadJSON UTF8String], (char *) [tokensJSON UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
#if DEBUG
    NSLog(@"NotifyUsers() method called");
#endif
}

////////////////////////////////////////////////////////////////////
#pragma mark - SendLogs method
//////////////////////////////////////////////////////////////////// sendLogs
RCT_EXPORT_METHOD(sendLogs:(NSString *)dbJson) {
    // TODO: Implement SendLogs for iOS
#if DEBUG
    NSLog(@"SendLogs() method called, not implemented");
#endif
}

//////////////////////////////////////////////////////////////////// addPeer
RCT_EXPORT_METHOD(addPeer:(NSString *)enode
                  callback:(RCTResponseSenderBlock)callback) {
  char * result = AddPeer((char *) [enode UTF8String]);
  callback(@[[NSString stringWithUTF8String: result]]);
#if DEBUG
  NSLog(@"AddPeer() method called");
#endif
}

//////////////////////////////////////////////////////////////////// updateMailservers
RCT_EXPORT_METHOD(updateMailservers:(NSString *)enodes
                  callback:(RCTResponseSenderBlock)callback) {
  char * result = UpdateMailservers((char *) [enodes UTF8String]);
  callback(@[[NSString stringWithUTF8String: result]]);
#if DEBUG
  NSLog(@"UpdateMailservers() method called");
#endif
}

//////////////////////////////////////////////////////////////////// recoverAccount
RCT_EXPORT_METHOD(recoverAccount:(NSString *)passphrase
                  password:(NSString *)password
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"RecoverAccount() method called");
#endif
    char * result = RecoverAccount((char *) [password UTF8String], (char *) [passphrase UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

//////////////////////////////////////////////////////////////////// login
RCT_EXPORT_METHOD(login:(NSString *)address
                  password:(NSString *)password
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"Login() method called");
#endif
    char * result = Login((char *) [address UTF8String], (char *) [password UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

//////////////////////////////////////////////////////////////////// login
RCT_EXPORT_METHOD(verify:(NSString *)address
                  password:(NSString *)password
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"VerifyAccountPassword() method called");
#endif
    NSFileManager *fileManager = [NSFileManager defaultManager];
    NSURL *rootUrl =[[fileManager
                      URLsForDirectory:NSDocumentDirectory inDomains:NSUserDomainMask]
                     lastObject];
    NSURL *absKeystoreUrl = [rootUrl URLByAppendingPathComponent:@"keystore"];
    
    char * result = VerifyAccountPassword((char *) [absKeystoreUrl.path UTF8String],
                                          (char *) [address UTF8String],
                                          (char *) [password UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - SendTransaction
//////////////////////////////////////////////////////////////////// sendTransaction
RCT_EXPORT_METHOD(sendTransaction:(NSString *)txArgsJSON
                  password:(NSString *)password
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"SendTransaction() method called");
#endif
    char * result = SendTransaction((char *) [txArgsJSON UTF8String], (char *) [password UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - SignMessage
//////////////////////////////////////////////////////////////////// signMessage
RCT_EXPORT_METHOD(signMessage:(NSString *)message
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"SignMessage() method called");
#endif
    char * result = SignMessage((char *) [message UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - SignGroupMembership
//////////////////////////////////////////////////////////////////// signGroupMembership
RCT_EXPORT_METHOD(signGroupMembership:(NSString *)content
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"SignGroupMembership() method called");
#endif
    char * result = SignGroupMembership((char *) [content UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - ExtractGroupMembershipSignatures
//////////////////////////////////////////////////////////////////// extractGroupMembershipSignatures
RCT_EXPORT_METHOD(extractGroupMembershipSignatures:(NSString *)content
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"ExtractGroupMembershipSignatures() method called");
#endif
    char * result = ExtractGroupMembershipSignatures((char *) [content UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - EnableInstallation
//////////////////////////////////////////////////////////////////// enableInstallation
RCT_EXPORT_METHOD(enableInstallation:(NSString *)content
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"EnableInstallation() method called");
#endif
    char * result = EnableInstallation((char *) [content UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - DisableInstallation
//////////////////////////////////////////////////////////////////// disableInstallation
RCT_EXPORT_METHOD(disableInstallation:(NSString *)content
                  callback:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"DisableInstallation() method called");
#endif
    char * result = DisableInstallation((char *) [content UTF8String]);
    callback(@[[NSString stringWithUTF8String: result]]);
}

////////////////////////////////////////////////////////////////////
#pragma mark - only android methods
////////////////////////////////////////////////////////////////////
RCT_EXPORT_METHOD(setAdjustResize) {
#if DEBUG
    NSLog(@"setAdjustResize() works only on Android");
#endif
}

RCT_EXPORT_METHOD(setAdjustPan) {
#if DEBUG
    NSLog(@"setAdjustPan() works only on Android");
#endif
}

RCT_EXPORT_METHOD(setSoftInputMode: (NSInteger) i) {
#if DEBUG
    NSLog(@"setSoftInputMode() works only on Android");
#endif
}

RCT_EXPORT_METHOD(clearCookies) {
    NSHTTPCookie *cookie;
    NSHTTPCookieStorage *storage = [NSHTTPCookieStorage sharedHTTPCookieStorage];
    for (cookie in [storage cookies]) {
        [storage deleteCookie:cookie];
    }
}

RCT_EXPORT_METHOD(clearStorageAPIs) {
    [[NSURLCache sharedURLCache] removeAllCachedResponses];

    NSString *path = [NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, YES) lastObject];
    NSArray *array = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:path error:nil];
    for (NSString *string in array) {
        NSLog(@"Removing %@", [path stringByAppendingPathComponent:string]);
        if ([[string pathExtension] isEqualToString:@"localstorage"])
            [[NSFileManager defaultManager] removeItemAtPath:[path stringByAppendingPathComponent:string] error:nil];
    }
}

RCT_EXPORT_METHOD(callRPC:(NSString *)payload
                  callback:(RCTResponseSenderBlock)callback) {
    dispatch_async( dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        char * result = CallRPC((char *) [payload UTF8String]);
        dispatch_async( dispatch_get_main_queue(), ^{
            callback(@[[NSString stringWithUTF8String: result]]);
        });
    });
}

RCT_EXPORT_METHOD(callPrivateRPC:(NSString *)payload
                  callback:(RCTResponseSenderBlock)callback) {
    dispatch_async( dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        char * result = CallPrivateRPC((char *) [payload UTF8String]);
        dispatch_async( dispatch_get_main_queue(), ^{
            callback(@[[NSString stringWithUTF8String: result]]);
        });
    });
}

RCT_EXPORT_METHOD(closeApplication) {
    exit(0);
}


RCT_EXPORT_METHOD(connectionChange:(NSString *)type
                       isExpensive:(BOOL)isExpensive) {
#if DEBUG
    NSLog(@"ConnectionChange() method called");
#endif
    ConnectionChange((char *) [type UTF8String], isExpensive? 1 : 0);
}

RCT_EXPORT_METHOD(appStateChange:(NSString *)type) {
#if DEBUG
    NSLog(@"AppStateChange() method called");
#endif
    AppStateChange((char *) [type UTF8String]);
}

RCT_EXPORT_METHOD(getDeviceUUID:(RCTResponseSenderBlock)callback) {
#if DEBUG
    NSLog(@"getDeviceUUID() method called");
#endif
    NSString* Identifier = [[[UIDevice currentDevice] identifierForVendor] UUIDString];

    callback(@[Identifier]);
}

+ (void)signalEvent:(const char *) signal
{
    if(!signal){
#if DEBUG
        NSLog(@"SignalEvent nil");
#endif
        return;
    }

    NSString *sig = [NSString stringWithUTF8String:signal];
#if DEBUG
    NSLog(@"SignalEvent");
    NSLog(sig);
#endif
    [bridge.eventDispatcher sendAppEventWithName:@"gethEvent"
                                            body:@{@"jsonEvent": sig}];

    return;
}

- (bool) is24Hour
{
    NSString *format = [NSDateFormatter dateFormatFromTemplate:@"j" options:0 locale:[NSLocale currentLocale]];
    return ([format rangeOfString:@"a"].location == NSNotFound);
}

- (NSDictionary *)constantsToExport
{
    return @{
             @"is24Hour": @(self.is24Hour),
             };
}

+ (BOOL)requiresMainQueueSetup
{
    return NO;
}

@end
