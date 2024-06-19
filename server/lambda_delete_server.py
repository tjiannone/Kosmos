""" Lambda to delete ec2-instances """
import boto3
import os

REGION =  os.environ['REGION']

EC2 = boto3.client('ec2', region_name=REGION)

def lambda_handler(event, context):
    
    serverid = '' 
    
    try:
        if (event['queryStringParameters']) and (event['queryStringParameters']['serverid']) and (
                event['queryStringParameters']['serverid'] is not None):
            serverid = event['queryStringParameters']['serverid']
            
        instance = EC2.terminate_instances(
        InstanceIds=[
            serverid 
            ],
        )
        
    except KeyError:
        print('No server id')
    
    res = {
        "statusCode": 200,
        "headers": {
            "Content-Type": "*/*"
        },
        "body": "The server id, " + serverid + " has been terminated!"
    }
    
    return res

