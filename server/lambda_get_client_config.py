import json
import boto3
import os

s3 = boto3.client('s3')

def lambda_handler(event, context):
    bucket =  os.environ['S3_BUCKET']
    key = os.environ['S3_KEY']
    json_data = ''
    
    try:
        data = s3.get_object(Bucket=bucket, Key=key)
        json_data = data['Body'].read().decode('utf-8')
        print (json_data)
    except Exception as e:
        raise e
    
    return {
        "statusCode": 200,
        "headers": {
            "Content-Type": "application/json"
        },
        "body": json_data
    }
