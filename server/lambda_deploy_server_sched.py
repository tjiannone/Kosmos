""" Lambda to launch ec2-instances """
import boto3
import os
import time
import json
import requests
import datetime

AMI=os.environ['AMI']
INSTANCE_TYPE = os.environ['INSTANCE_TYPE'] #These will be environment variables that we must specify in lambda
KEY_NAME = os.environ['KEY_NAME']
SUBNET_ID = os.environ['SUBNET_ID']
REGION =  os.environ['REGION']
SECURITY_GROUP_ID = os.environ['SECURITY_GROUP_ID']
IAM_ROLE = os.environ['IAM_ROLE']
TO_ADDRESS = os.environ.get('TO_ADDRESS')
FROM_ADDRESS = os.environ.get('FROM_ADDRESS')

EC2 = boto3.client('ec2', region_name=REGION)
ssm = boto3.client('ssm', region_name=REGION)   
client = boto3.client('ses', region_name=REGION)
s3 = boto3.resource('s3')

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
        IamInstanceProfile={
        'Name': IAM_ROLE
        },
        MinCount=1, # required by boto, even though it's kinda obvious.
        MaxCount=1,
        #DryRun=True,
        UserData = init_script, # file to run on instance init.
        TagSpecifications=[{    #This creates a tag for our resource
            'ResourceType': 'instance',
            'Tags': [{'Key': 'Name','Value': 'Kosmos Server'}]
        }]   
    )

    print("New instance created.")
    instance_id = instance['Instances'][0]['InstanceId']
    print(instance_id)
    
    current_time = str(datetime.datetime.now())
    
    time.sleep(60) #Wait 30 seconds to complete EC2 Kosmos configuration
    
    print('Into DescribeEc2Instance')
    instances = EC2.describe_instances(Filters=[{'Name': 'instance-id', 'Values': [instance_id]}])
    reservations = instances['Reservations']
    
    exec_list=[]
    
    #Iterate through all the instances within the collected resaervations
    #Append 'running' instances to exec list, ignoring 'stopped' and 'terminated' ones'
    for reservation in reservations:
        for instance in reservation['Instances']:
            print(instance['InstanceId'], " is ", instance['State']['Name'])
            if instance['State']['Name'] == 'running':
                exec_list.append(instance['InstanceId'])
        #divider between reservations
        print("**************") 
        #print(exec_list)
    
    script = "cat /etc/cloak/shadowsocks.json"
    
    time.sleep(15) #Wait 15 seconds to complete EC2 Kosmos configuration
    
    response = ssm.send_command(
        DocumentName ='AWS-RunShellScript',
        Parameters = {'commands': [script]},
        InstanceIds = exec_list
    )
    
    #See the command run on the target instance Ids
    print(response['Command']['Parameters']['commands'])
    #print(response)
    
    time.sleep(2)
    print("start get command invocation")
    cmd_id= response['Command']['CommandId']
    response = ssm.get_command_invocation(CommandId=cmd_id, 
        InstanceId=exec_list[0])
    data = response['StandardOutputContent']
    print(data)
    
    #Upload server configuration to S3 for API consumption and flag for EC2 termination
    data_json = json.loads(data)
    obj = s3.Object('kosmosgapbucket', 'kosmosclientconfig')
    obj.put(Body=(bytes(json.dumps(data_json).encode('UTF-8'))))
    obj = s3.Object('kosmosgapec2', instance_id)
    obj.put(Body=(bytes(json.dumps(data_json).encode('UTF-8'))))
    
    #Sending email confirmation to Admin that EC2 has been successfully created
    bodytext = 'The server ' + instance_id + ' has been successfully deployed at ' + current_time + '.\n' + 'Configuration:\n' + str(data_json)
    subjecttext = 'Notification: Server ' + instance_id + ' deployed at ' + current_time
    
    response1 = client.send_email(
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
    
    return {
        "statusCode": 200,
        "headers": {
            "Content-Type": "application/json"
        },
        "body": data_json
    }
