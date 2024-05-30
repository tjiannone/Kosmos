""" Lambda to launch ec2-instances """
import boto3
import os

AMI=os.environ['AMI']
INSTANCE_TYPE = os.environ['INSTANCE_TYPE'] #These will be environment variables that we must specify in lambda
KEY_NAME = os.environ['KEY_NAME']
SUBNET_ID = os.environ['SUBNET_ID']
REGION =  os.environ['REGION']
SECURITY_GROUP_ID = os.environ['SECURITY_GROUP_ID']

EC2 = boto3.client('ec2', region_name=REGION)

def lambda_handler(event, context):
    init_script = """#!/bin/bash
                sudo su -
                git clone https://github.com/ariskumara/Kosmos-Server.git
                cd Kosmos-Server
                bash Cloak2-Installer.sh"""
    print(init_script)

    instance = EC2.run_instances(
        ImageId=AMI,
        InstanceType=INSTANCE_TYPE,
        KeyName = KEY_NAME,
        SubnetId = SUBNET_ID,
        SecurityGroupIds=[SECURITY_GROUP_ID],
        MinCount=1, # required by boto, even though it's kinda obvious.
        MaxCount=1,
        UserData = init_script, # file to run on instance init.
        TagSpecifications=[{    #This creates a tag for our resource
            'ResourceType': 'instance',
            'Tags': [{'Key': 'Name','Value': 'Shadowcloak'}]
        }]   
    )

    print("New instance created.")
    instance_id = instance['Instances'][0]['InstanceId']
    print(instance_id)

    return(instance_id)
