

import glob
import subprocess
import os
import platform
from config import configurations
import argparse
import time

def run_integration_test(config, test_file):
    process_parameters = ['cetus_wsl',*config['parameters'], test_file]
    test_result = subprocess.call(process_parameters, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    base_name = os.path.basename(test_file)
    base_name = base_name.split("test_").pop()
    validation_file_name = "case_" + base_name
    validation_file_path = os.path.join(config['validation_folder'], validation_file_name)

    generated_file = os.path.join("./cetus_output", f"test_{base_name}")
   
    if platform.system() == "Windows":
        validation_command = ['fc', '/b', generated_file, validation_file_path]
    else:
        validation_command = ['diff', '-q',
                              generated_file, validation_file_path]

    start_time = time.perf_counter()  # More precise timing

    test_result = subprocess.call(
        validation_command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    
    end_time = time.perf_counter()
    duration = end_time - start_time

    return [test_result, duration]

def main(config, val='Y'):
    parameters = config['parameters']
    validation_folder = config['validation_folder']
    test_folder = config['test_folder']
    
    print("Parameters:", parameters)
    print("Validation Folder:", validation_folder)
    print("Test Folder:", test_folder)
    files = glob.glob(f"{config['test_folder']}/test_*.c")



    if (val == 'N'):
        test_file = input("Specify a test file to run: ")
        test_result,duration = run_integration_test(config, test_file)
        if (test_result != 0):
            print("Test for: ", test_file, " failed!")
        else:
            print("Test for: ", test_file, " passed!")
        return
    
    print("Running all tests in this directory......")

    failed_tests = 0
    list_failed_tests = []

    test_durations = []
    for test_file in files:
        print("Running test: ", test_file)
        test_result, test_duration = run_integration_test(config, test_file)
        test_durations.append(test_duration)
        if (test_result != 0):
            failed_tests = failed_tests + 1
            list_failed_tests.append(test_file)
            
    duration = sum(test_durations)
    av_duration = duration / len(test_durations)
    print(f"Integration tests took {duration:.2f} seconds.")
    print(f"Average tests time {av_duration:.2f} seconds.")
    
    print("**********************************************\n")
    print("\nFound", len(files), "test cases")
    print("Passed = ", len(files) - failed_tests)

    print("Failed = ", failed_tests)
    

    if (list_failed_tests):
        print("Following tests failed:")

        for failed_test in list_failed_tests:
            print('  ', failed_test)
        

    
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Cetus Regression Script")
    parser.add_argument('--config', type=str, required=False, help='Configuration file path')

    parser.add_argument('--run_all', type=str, required=False, help='Run all tests in the directory (Y/N)')
    args = parser.parse_args()

    if args.config:
        import importlib.util
        spec = importlib.util.spec_from_file_location("config", args.config)
        config_module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(config_module)
        config = config_module.configurations
    else:
        # Use the default configuration
        config = configurations

    main(config, args.run_all)