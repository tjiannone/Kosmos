""" Lambda to launch ec2-instances """
import boto3
import os

REGION =  os.environ['REGION']

EC2 = boto3.client('ec2', region_name=REGION)

def lambda_handler(event, context):
    instance = EC2.terminate_instances(
    InstanceIds=[
        '[instance_id]' 
        ],
    )

