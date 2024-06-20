import json
import boto3
import os
import logging
import boto3
import botocore
import requests
from botocore.exceptions import ClientError

client = boto3.client('ses', region_name='us-east-1')

def lambda_handler(event, context):
    request_body = json.loads(event['body']) # EXtract the Body from the call
    request_msg = json.dumps(request_body['message'])#['chat']['id'] # Extract the message object which contrains chat id and text
    chat_id = json.dumps(request_body['message']['chat']['id']) # Extract the chat id from message
    command = json.dumps(request_body['message']['text']).strip('"') # Extract the text from the message
    # TODO implement
    BOT_TOKEN = os.environ.get('TOKEN')
    BOT_CHAT_ID = os.environ.get('CHATID')
    S3_BUCKET = os.environ.get('S3_BUCKET')
    APK_FILE = os.environ.get('APK_FILE')
    TO_ADDRESS = os.environ.get('TO_ADDRESS')
    FROM_ADDRESS = os.environ.get('FROM_ADDRESS')
    BOT_CHAT_ID = chat_id # Updating the Bot Chat Id to be dynamic instead of static one earlier
    command = command[1:] # Valid input command is /start or /help. however stripping the '/' here as it was having some conflict in execution.
    if command == 'start':
        message = "Welcome to Kosmos bot! How can I help you today?" # Sample Response on start command
    elif command == 'help':
        message = "Here are the available commands: /start, /help, /report and /download"
    elif command =='download':
        url = create_presigned_url(S3_BUCKET,APK_FILE,300)
        print(url)  
        data = {'url': url}
        payload=requests.post('https://cleanuri.com/api/v1/shorten',data=data)
        short_url=payload.json()['result_url']
        print("The short url is : {}".format(short_url))
        message = "Here is the link to download that is valid for 5 mins: " + short_url
    elif command == "report":
        response1 = client.send_email(
        Destination={
            'ToAddresses': [TO_ADDRESS]
        },
        Message={
            'Body': {
                'Text': {
                    'Charset': 'UTF-8',
                    'Data': 'You received report that Kosmos server is not working',
                }
            },
            'Subject': {
                'Charset': 'UTF-8',
                'Data': 'Report: Kosmos Server is not working',
            },
        },
        Source=FROM_ADDRESS
        )
        message = "Your report is successfully sent to info@kosmosgap.com We will verify it. If we confirmed there is an issue in our server, we will fix it asap"
    else:
        message = "I'm sorry, I didn't understand that command. Please try again."
    send_text = 'https://api.telegram.org/bot' + BOT_TOKEN + '/sendMessage?chat_id=' + BOT_CHAT_ID + '&parse_mode=HTML&text=' + message
    print(send_text)
    response = requests.get(send_text)
    print(send_text)
    print(response)
    return {
        'statusCode': 200,
        'body': json.dumps('Hello from Lambda!')
    }
    
def create_presigned_url(bucket_name, object_name, expiration=600):
    # Generate a presigned URL for the S3 object
    s3_client = boto3.client('s3',region_name="us-east-1",config=boto3.session.Config(signature_version='s3v4',))
    try:
        response = s3_client.generate_presigned_url('get_object',
                                                    Params={'Bucket': bucket_name,
                                                            'Key': object_name},
                                                    ExpiresIn=expiration)
    except Exception as e:
        print(e)
        logging.error(e)
        return "Error"
    # The response contains the presigned URL
    return response
