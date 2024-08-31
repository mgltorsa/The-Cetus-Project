

import glob
import subprocess
import os
import platform
from config import configurations
import argparse
import time

def run_integration_test(test_file):
    process_parameters = ['../../bin/cetus',*configurations['parameters'], test_file]
    test_result = subprocess.call(process_parameters, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    base_name = os.path.basename(test_file)
    base_name = base_name.split("test_").pop()
    validation_file_name = "case_" + base_name
    validation_file_path = os.path.join(configurations['validation_folder'], validation_file_name)

    generated_file = os.path.join("./cetus_output", test_file)

    if platform.system() == "Windows":
        validation_command = ['fc', '/b', generated_file, validation_file_path]
    else:
        validation_command = ['diff', '-q',
                              generated_file, validation_file_path]

    test_result = subprocess.call(
        validation_command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    return test_result

def main(config):
    parameters = config['parameters']
    validation_folder = config['validation_folder']
    test_folder = config['test_folder']
    
    print("Parameters:", parameters)
    print("Validation Folder:", validation_folder)
    print("Test Folder:", test_folder)
    files = glob.glob(f"{configurations['test_folder']}/test_*.c")

    val = input("Do you want to run all tests? Y or N: ")


    if (val == 'Y'):
        print("Running all tests in this directory......")

        failed_tests = 0
        list_failed_tests = []

        start_time = time.time()
        for test_file in files:
            test_result = run_integration_test(test_file)
            if (test_result != 0):
                failed_tests = failed_tests + 1
                list_failed_tests.append(test_file)
                
        end_time = time.time()
        duration = end_time - start_time
        print(f"Integrationt tests took {duration:.2f} seconds.")
        
        print("**********************************************\n")
        print("\nFound", len(files), "test cases")
        print("Passed = ", len(files) - failed_tests)

        print("Failed = ", failed_tests)
        

        if (list_failed_tests):
            print("Following tests failed:")

            for failed_test in list_failed_tests:
                print('  ', failed_test)

    elif (val == 'N'):
        test_file = input("Specify a test file to run: ")
        test_result = run_integration_test(test_file)
        if (test_result != 0):
            print("Test for: ", test_file, " failed!")
        else:
            print("Test for: ", test_file, " passed!")

    
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Cetus Regression Script")
    parser.add_argument('--config', type=str, required=False, help='Configuration file path')

    args = parser.parse_args()

    if args.config:
        # If a custom configuration is provided, load it
        import importlib.util
        spec = importlib.util.spec_from_file_location("config", args.config)
        config_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(config_module)
        config = config_module.configurations
    else:
        # Use the default configuration
        config = configurations

    main(config)