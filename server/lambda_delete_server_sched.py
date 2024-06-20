""" Lambda to terminate ec2-instances """
import boto3
import os
import json
import datetime
import time

REGION =  os.environ['REGION']
TO_ADDRESS = os.environ.get('TO_ADDRESS')
FROM_ADDRESS = os.environ.get('FROM_ADDRESS')
S3_BUCKET = os.environ['S3_BUCKET']

ec2 = boto3.client('ec2', region_name=REGION)
ses = boto3.client('ses', region_name=REGION)
s3 = boto3.client('s3', region_name=REGION)
serverid = ''

def lambda_handler(event, context):
    
    #get serverid that we want to terminate
    #for key in s3.list_objects(Bucket=S3_BUCKET)['Contents']:
    #    serverid = key['Key']
    #    print(key['Key'])
    
    paginator = s3.get_paginator('list_objects_v2')
    response_iterator = paginator.paginate(Bucket=S3_BUCKET)
    objects = []
    
    for response in response_iterator:
        if 'Contents' in response:
            for obj in response['Contents']:
                objects.append(obj)
    sorted_objects = sorted(objects, key=lambda x: x['LastModified'], reverse=False)
    print(sorted_objects)
    serverid = sorted_objects[0]["Key"]
    print("Object to be deleted is ", serverid)
    
    time.sleep(1) #wait for 1 second

    #terminate instance in EC2
    instance = ec2.terminate_instances(
    InstanceIds=[
        serverid 
        ],
    )
    print(instance)
    #get current time
    current_time = str(datetime.datetime.now())
    
    time.sleep(1) #wait for 1 second
    
    #delete object in S3
    response = s3.delete_object(
        Bucket=S3_BUCKET,
        Key=serverid,
    )
    print(response)
    
    #Sending email confirmation to Admin that EC2 has been successfully terminated
    bodytext = 'The server ' + serverid + ' has been successfully terminated at ' + current_time + '.\n'
    subjecttext = 'Notification: Server ' + serverid + ' terminated at ' + current_time
    
    response1 = ses.send_email(
        Destination={
            'ToAddresses': [TO_ADDRESS]
        },
        Message={
            'Body': {
                'Text': {
                    'Charset': 'UTF-8',
                    'Data': bodytext
                }
            },
            'Subject': {
                'Charset': 'UTF-8',
                'Data': subjecttext
            },
        },
        Source=FROM_ADDRESS
        )
    print(response1)
    
    res = {
        "statusCode": 200,
        "headers": {
            "Content-Type": "*/*"
        },
        "body": "The server id, " + serverid + " has been terminated!"
    }
    
    return res
