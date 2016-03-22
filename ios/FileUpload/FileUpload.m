#import "FileUpload.h"

#import <Foundation/Foundation.h>
#import <MobileCoreServices/MobileCoreServices.h>
#import <UIKit/UIKit.h>
#import <AssetsLibrary/AssetsLibrary.h>
#import "RCTImageLoader.h"
#import "RCTConvert.h"

#import "RCTLog.h"

@implementation FileUpload

RCT_EXPORT_MODULE();

@synthesize bridge = _bridge;

RCT_EXPORT_METHOD(upload:(NSDictionary *)obj callback:(RCTResponseSenderBlock)callback)
{
  NSString *uploadUrl = obj[@"uploadUrl"];
  NSDictionary *headers = obj[@"headers"];
  NSDictionary *fields = obj[@"fields"];
  NSArray *files = obj[@"files"];
  NSString *method = obj[@"method"];
  
  if ([method isEqualToString:@"POST"] || [method isEqualToString:@"PUT"]) {
  } else {
    method = @"POST";
  }
  
  NSURL *url = [NSURL URLWithString:uploadUrl];
  NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:url];
  [req setHTTPMethod:method];
  
  // set headers
  NSString *formBoundaryString = [self generateBoundaryString];
  NSString *contentType = [NSString stringWithFormat:@"multipart/form-data; boundary=%@", formBoundaryString];
  [req setValue:contentType forHTTPHeaderField:@"Content-Type"];
  for (NSString *key in headers) {
    id val = [headers objectForKey:key];
    if ([val respondsToSelector:@selector(stringValue)]) {
      val = [val stringValue];
    }
    if (![val isKindOfClass:[NSString class]]) {
      continue;
    }
    [req setValue:val forHTTPHeaderField:key];
  }
  
  
  NSData *formBoundaryData = [[NSString stringWithFormat:@"--%@\r\n", formBoundaryString] dataUsingEncoding:NSUTF8StringEncoding];
  NSMutableData* reqBody = [NSMutableData data];
  
  // add fields
  for (NSString *key in fields) {
    id val = [fields objectForKey:key];
    if ([val respondsToSelector:@selector(stringValue)]) {
      val = [val stringValue];
    }
    if (![val isKindOfClass:[NSString class]]) {
      continue;
    }
    
    [reqBody appendData:formBoundaryData];
    [reqBody appendData:[[NSString stringWithFormat:@"Content-Disposition: form-data; name=\"%@\"\r\n\r\n", key] dataUsingEncoding:NSUTF8StringEncoding]];
    [reqBody appendData:[val dataUsingEncoding:NSUTF8StringEncoding]];
    [reqBody appendData:[@"\r\n" dataUsingEncoding:NSUTF8StringEncoding]];
  }
  
  // add files
  for (NSDictionary *file in files) {
    NSString *name = file[@"name"];
    NSString *filename = file[@"filename"];
    NSString *filepath = file[@"filepath"];
    NSString *filetype = file[@"filetype"];
    
    __block NSData *fileData = nil;
    __block NSError *err = nil;
      
    dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
    
    if ([filepath hasPrefix:@"assets-library:"] ||[filepath hasPrefix:@"rct-image-store"]) {
        
        [_bridge.imageLoader loadImageWithTag:filepath callback:^(NSError *error, UIImage *image) {
            
            if (error) {
                err = error;
            }
            else {
                NSDictionary *size = file[@"size"];
                if (size) {
                    CGFloat width = [RCTConvert float:size[@"width"]];
                    CGFloat height = [RCTConvert float:size[@"height"]];
                    CGFloat scale = 1.0;
                    if (image.size.width>0 && image.size.height>0) {
                        scale = MAX(width/(image.size.width *image.scale), height/(image.size.height * image.scale));
                    }
                    image = [self scaledImage:image scale:scale];
                }
                
                BOOL png = [RCTConvert BOOL:file[@"png"]];
                if (png) {
                    fileData = UIImagePNGRepresentation(image);
                }
                else {
                    CGFloat compress = [RCTConvert float:file[@"compress"]];
                    if (compress == 0) {
                        compress = 1.0f;
                    }
                    fileData = UIImageJPEGRepresentation(image, compress);
                }
            }
            dispatch_semaphore_signal(semaphore);
        }];
        
        dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER);
        if (err) {
            callback(@[err.description]);
        }

    } else if ([filepath hasPrefix:@"data:"] || [filepath hasPrefix:@"file:"]) {
      NSURL *fileUrl = [[NSURL alloc] initWithString:filepath];
      fileData = [NSData dataWithContentsOfURL: fileUrl];
    } else {
      fileData = [NSData dataWithContentsOfFile:filepath];
    }
    
    [reqBody appendData:formBoundaryData];
    [reqBody appendData:[[NSString stringWithFormat:@"Content-Disposition: form-data; name=\"%@\"; filename=\"%@\"\r\n", name.length ? name : filename, filename] dataUsingEncoding:NSUTF8StringEncoding]];
    
    if (filetype) {
      [reqBody appendData:[[NSString stringWithFormat:@"Content-Type: %@\r\n", filetype] dataUsingEncoding:NSUTF8StringEncoding]];
    } else {
      [reqBody appendData:[[NSString stringWithFormat:@"Content-Type: %@\r\n", [self mimeTypeForPath:filename]] dataUsingEncoding:NSUTF8StringEncoding]];
    }
    
    [reqBody appendData:[[NSString stringWithFormat:@"Content-Length: %ld\r\n\r\n", (long)[fileData length]] dataUsingEncoding:NSUTF8StringEncoding]];
    [reqBody appendData:fileData];
    [reqBody appendData:[@"\r\n" dataUsingEncoding:NSUTF8StringEncoding]];
  }
  
  // add end boundary
  NSData* end = [[NSString stringWithFormat:@"--%@--\r\n", formBoundaryString] dataUsingEncoding:NSUTF8StringEncoding];
  [reqBody appendData:end];
  
  // send request
  [req setHTTPBody:reqBody];
  NSHTTPURLResponse *response = nil;
  NSData *returnData = [NSURLConnection sendSynchronousRequest:req returningResponse:&response error:nil];
  NSInteger statusCode = [response statusCode];
  NSString *returnString = [[NSString alloc] initWithData:returnData encoding:NSUTF8StringEncoding];
  
  NSDictionary *res=[[NSDictionary alloc] initWithObjectsAndKeys:[NSNumber numberWithInteger:statusCode],@"status",returnString,@"data",nil];
  
  callback(@[[NSNull null], res]);
}

- (NSString *)generateBoundaryString
{
  NSString *uuid = [[NSUUID UUID] UUIDString];
  return [NSString stringWithFormat:@"----%@", uuid];
}

- (NSString *)mimeTypeForPath:(NSString *)filepath
{
  NSString *fileExtension = [filepath pathExtension];
  NSString *UTI = (__bridge_transfer NSString *)UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension, (__bridge CFStringRef)fileExtension, NULL);
  NSString *contentType = (__bridge_transfer NSString *)UTTypeCopyPreferredTagWithClass((__bridge CFStringRef)UTI, kUTTagClassMIMEType);
  
  if (contentType) {
    return contentType;
  }
  return @"application/octet-stream";
}

- (UIImage *)scaledImage:(UIImage *)image scale:(CGFloat)scale
{
  if (scale >= 1.0) {
    return image;
  }
  if (scale==0) {
    scale = 1.0/[UIScreen mainScreen].scale;
  }
  CGFloat width = image.size.width * scale;
  CGFloat height = image.size.height * scale;
  
  UIGraphicsBeginImageContext(CGSizeMake(width, height));
  [image drawInRect:CGRectMake(0, 0, width, height)];
  UIImage *scaledImage = UIGraphicsGetImageFromCurrentImageContext();
  UIGraphicsEndImageContext();
  return scaledImage;
}

@end
